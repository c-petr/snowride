package cz.hudecekpetr.snowride.output;

import cz.hudecekpetr.snowride.fx.SnowAlert;
import cz.hudecekpetr.snowride.settings.Settings;
import cz.hudecekpetr.snowride.tree.LogicalLine;
import cz.hudecekpetr.snowride.tree.highelements.HighElement;
import cz.hudecekpetr.snowride.tree.highelements.Scenario;
import cz.hudecekpetr.snowride.tree.highelements.Suite;
import cz.hudecekpetr.snowride.tree.sections.KeyValuePairSection;
import cz.hudecekpetr.snowride.tree.sections.SectionKind;
import cz.hudecekpetr.snowride.ui.Images;
import cz.hudecekpetr.snowride.ui.MainForm;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.image.ImageView;
import org.apache.commons.lang3.StringUtils;
import org.robotframework.jaxb.BodyItemStatusValue;
import org.robotframework.jaxb.Keyword;
import org.robotframework.jaxb.KeywordType;
import org.robotframework.jaxb.OutputElement;
import org.robotframework.jaxb.OutputSuite;
import org.robotframework.jaxb.Robot;
import org.robotframework.jaxb.StatusValue;
import org.robotframework.jaxb.Test;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

public class OutputParser {

    public static void cleanup() {
        if (Settings.getInstance().disableOutputParsing) {
            return;
        }
        // cleanup
        MainForm.INSTANCE.getRootElement().childrenRecursively.forEach(highElement -> {
            highElement.outputElement = null;
            // cleanup changed icons of Suites
            if (highElement instanceof Suite) {
                highElement.treeNode.setGraphic(highElement.treeNodeGraphic);
            }
        });
        MainForm.INSTANCE.navigationStack.clearOutputElementStack();

        // update line number cells style
        MainForm.INSTANCE.gridTab.updateTablesLineNumberCellsStyle();
    }

    public static void parseOutput(File outputXml) {
        if (Settings.getInstance().disableOutputParsing) {
            return;
        }
        try {
            Unmarshaller unmarshaller = JAXBContext.newInstance(Robot.class).createUnmarshaller();
            Robot result = (Robot) unmarshaller.unmarshal(outputXml);

            if (!Settings.getInstance().disableOutputParsingWarning) {
                // Show warning in case output.xml was generated by Robot Framework older than 3.x
                String robotFrameworkVersionString = StringUtils.substringBetween(result.getGenerator(), "Robot ", "(");
                int robotFrameworkVersion = Integer.parseInt(String.valueOf(robotFrameworkVersionString.charAt(0)));
                if (robotFrameworkVersion < 3) {
                    Platform.runLater(() -> {
                        ButtonType proceedAnyway = new ButtonType("Proceed anyway");
                        ButtonType cancel = new ButtonType("Ok", ButtonBar.ButtonData.CANCEL_CLOSE);
                        SnowAlert alert = new SnowAlert(Alert.AlertType.WARNING,
                                "Unsupported output.xml format (Robot " + robotFrameworkVersion + "). Only version Robot 3.x+ is supported. " +
                                        "You can 'Disable output.xml parsing' or showing this warning in Snowride settings.", proceedAnyway, cancel);
                        alert.setHeaderText("Unsupported Robot Framework version");

                        Optional<ButtonType> alertResult = alert.showAndWait();
                        if (!alertResult.isPresent() || alertResult.get() == proceedAnyway) {
                            preprocess(result);
                        }
                    });
                    return;
                }
            }

            preprocess(result);
        } catch (JAXBException e) {
            e.printStackTrace();
        }
    }

    private static void preprocess(Robot result) {
        preprocess(null, result.getSuite());

        //  ensure to "update" of TableView content for settings/variables/spreadsheet after output.xml is processed
        MainForm.INSTANCE.gridTab.updateTablesLineNumberCellsStyle();
    }

    private static void preprocess(OutputElement parent, OutputElement element) {
        element.parent = parent;
        if (element instanceof OutputSuite) {
            HighElement highElement = findHighElementAndAssignOutput(element);

            // change icon of Suite when Suite/Test SETUP/TEARDOWN is failing
            OutputSuite suite = (OutputSuite) element;
            if (highElement != null) {
                if (suite.getKeywords().stream().anyMatch(keyword -> keyword.getStatus().getStatus() == BodyItemStatusValue.FAIL)) {
                    // Suite SETUP/TEARDOWN
                    changeGraphicsOfSuiteElement(highElement);
                }
            }

            // pre-process recursively
            element.getElements().forEach(current -> preprocess(element, current));
        }
        if (element instanceof Test) {
            HighElement highElement = findHighElementAndAssignOutput(element);
            Test test = (Test) element;
            if (test.getStatus().getStatus() == StatusValue.FAIL) {
                List<Keyword> failingSetupTeardown = test.getFailingSetupTeardown();
                if (!failingSetupTeardown.isEmpty()) {
                    if (highElement instanceof Scenario) {
                        Scenario scenario = (Scenario) highElement;
                        for (Keyword keyword : failingSetupTeardown) {
                            String toMatch = "";
                            switch (keyword.getType()) {
                                case SETUP:
                                case SETUP_RF3:
                                    toMatch = "[Setup]";
                                    break;
                                case TEARDOWN:
                                case TEARDOWN_RF3:
                                    toMatch = "[Teardown]";
                                    break;
                            }
                            if (findLineStartingWith(scenario.getLines(), toMatch, 1) == null) {
                                if (keyword.getType() == KeywordType.SETUP || keyword.getType() == KeywordType.SETUP_RF3) {
                                    keyword.setType(KeywordType.TEST_SETUP);
                                } else if (keyword.getType() == KeywordType.TEARDOWN || keyword.getType() == KeywordType.TEARDOWN_RF3) {
                                    keyword.setType(KeywordType.TEST_TEARDOWN);
                                }
                                test.getKwOrForOrIf().remove(keyword);
                                findAndAttachFailingTestSetupTeardownToSuite(element, keyword);
                            }
                        }
                    }
                }
            }
            element.getElements().forEach(current -> preprocess(element, current));
        } else {
            element.getElements().forEach(current -> preprocess(element, current));
        }
    }

    private static void findAndAttachFailingTestSetupTeardownToSuite(OutputElement outputElement, Keyword keyword) {
        if (outputElement.parent instanceof OutputSuite) {
            OutputSuite outputSuite = (OutputSuite) outputElement.parent;
            HighElement suiteHighElement = findHighElementAndAssignOutput(outputSuite);

            if (suiteHighElement instanceof Suite) {
                if (outputSuite.getKeywords().stream().anyMatch(suiteKeyword -> suiteKeyword.getType() == keyword.getType())) {
                    // failing 'Setup/Teardown' already added by other test
                    return;
                }

                Suite suite = (Suite) suiteHighElement;
                suite.fileParsed.sections.stream().filter(robotSection -> robotSection.header.sectionKind == SectionKind.SETTINGS).findFirst().ifPresent(robotSection -> {
                    if (robotSection instanceof KeyValuePairSection) {
                        KeyValuePairSection section = (KeyValuePairSection) robotSection;

                        String toMatch = "Test Setup";
                        switch (keyword.getType()) {
                            case TEARDOWN:
                            case TEST_TEARDOWN:
                                toMatch = "Test Teardown";
                                break;
                        }

                        LogicalLine line = findLineStartingWith(section.pairs, toMatch, 0);
                        if (line == null) {
                            findAndAttachFailingTestSetupTeardownToSuite(outputSuite, keyword);
                        } else {
                            outputSuite.getKeywords().add(keyword);
                            changeGraphicsOfSuiteElement(suiteHighElement);
                        }
                    }
                });
            }
        }
    }


    private static HighElement findHighElementAndAssignOutput(OutputElement input) {
        HighElement highElement = findHighElement(input);
        if (highElement != null) {
            highElement.outputElement = input;
        }
        return highElement;
    }

    private static HighElement findHighElement(OutputElement input) {
        for (HighElement highElement : MainForm.INSTANCE.getRootElement().childrenRecursively) {
            if (highElement.getQualifiedName().equalsIgnoreCase(input.getFullName())) {
                return highElement;
            }
        }
        return null;
    }

    private static void changeGraphicsOfSuiteElement(HighElement highElement) {
        highElement.treeNode.setGraphic(new ImageView(Images.error));
    }

    private static LogicalLine findLineStartingWith(ObservableList<LogicalLine> lines, String toMatch, int cellIndex) {
        return lines.stream().filter(line -> line.cells.size() > cellIndex && line.cells.get(cellIndex).contents.equalsIgnoreCase(toMatch)).findFirst().orElse(null);
    }
}
