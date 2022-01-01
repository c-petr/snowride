package cz.hudecekpetr.snowride.ui;

import cz.hudecekpetr.snowride.filesystem.LastChangeKind;
import cz.hudecekpetr.snowride.tree.highelements.HighElement;
import cz.hudecekpetr.snowride.tree.highelements.Scenario;
import cz.hudecekpetr.snowride.tree.highelements.Suite;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.fxmisc.flowless.VirtualizedScrollPane;

public class TextEditTab {

    private final Node warningPane;
    private VBox editorPane;
    private final MainForm mainForm;
    private Tab tabTextEdit;
    private HighElement lastLoaded;
    private Scenario lastLoadedScenario;
    private boolean cleanLastLoadedScenario = true;
    private final SnowCodeAreaProvider codeAreaProvider = SnowCodeAreaProvider.INSTANCE;
    private boolean switchingSuite;

    public TextEditTab(MainForm mainForm) {
        this.mainForm = mainForm;
        // The warning pane is a remnant of an old solution where, if you tried to edit the text of a scenario,
        // it showed this warning pane. Now, it will programmatically select the suite that contains that scenario
        // instead. But in some edge cases, the warning pane can still be displayed.
        Label noEditForTestCases = new Label("You cannot edit test cases this way. Use the grid editor instead.");
        Button bOrSwitch = new Button("...or edit the entire suite as text");
        bOrSwitch.setOnAction(event -> mainForm.selectProgrammatically(mainForm.getProjectTree().getFocusModel().getFocusedItem().getValue().parent));
        VBox vboxWarning = new VBox(noEditForTestCases, bOrSwitch);
        HBox outer2 = new HBox(vboxWarning);
        outer2.setAlignment(Pos.CENTER);
        VBox outer1 = new VBox(outer2);
        outer1.setAlignment(Pos.CENTER);
        warningPane = outer1;
    }

    public Tab createTab() {
        // Apply changes
        Button bApply = new Button("Apply changes");
        Label lblInfo = new Label("Changes are applied automatically if you switch to another tab, test case, or suite; or if you save.");
        bApply.setOnAction(event -> {
            HighElement whatChanged = mainForm.getProjectTree().getFocusModel().getFocusedItem().getValue();
            whatChanged.applyText();
            if (whatChanged.asSuite().fileParsed != null && whatChanged.asSuite().fileParsed.errors.size() > 0) {
                throw new RuntimeException("There are parse errors. See the other tabs for details.");
            }
        });

        // Reformat
        Button bReformat = new Button("Reformat (Ctrl+L)");
        bReformat.setOnAction(event -> reformat());
        Tooltip.install(bReformat, new Tooltip("Reformats the file so that it looks as close as RIDE would reformat it. Does nothing if the file cannot be parsed."));
        mainForm.getStage().addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.L && event.isShortcutDown()) {
                reformat();
            }
        });

        HBox hBox = new HBox(2, SnowCodeAreaSearchBox.INSTANCE, bReformat, bApply, lblInfo);
        hBox.setPadding(new Insets(2));
        hBox.setAlignment(Pos.CENTER_LEFT);
        VBox textVBox = new VBox(hBox);

        editorPane = textVBox;
        tabTextEdit = new Tab("Text edit", textVBox);
        tabTextEdit.setClosable(false);
        return tabTextEdit;
    }

    private void reformat() {
        Suite reformattingWhat = lastLoaded.asSuite();
        reformattingWhat.applyText();
        if (reformattingWhat.fileParsed != null) {
            if (reformattingWhat.fileParsed.errors.size() > 0) {
                throw new RuntimeException("There are parse errors. Reformatting cannot happen.");
            }
            reformattingWhat.fileParsed.reformat();
            reformattingWhat.contents = reformattingWhat.serialize();
            mainForm.changeOccurredTo(reformattingWhat, LastChangeKind.TEXT_CHANGED);

        }
        loadElement(reformattingWhat);
    }

    public void loadElement(HighElement value) {
        if (value instanceof Suite && value.unsavedChanges == LastChangeKind.STRUCTURE_CHANGED) {
            Suite asSuite = (Suite) value;
            asSuite.optimizeStructure();
            asSuite.contents = asSuite.serialize();
        }

        // Decide if we are loading HighElement from the same suite or some other suite (used for navigation purposes between "Grid" and "Text" edit)
        switchingSuite = lastLoaded != value && (lastLoaded == null || !lastLoaded.children.contains(value)) && (value == null || !value.children.contains(lastLoaded));

        if (lastLoaded != null) {
            lastLoaded.applyText();
        }
        lastLoaded = value;

        if (cleanLastLoadedScenario) {
            lastLoadedScenario = null;
        }
        cleanLastLoadedScenario = true;

        if (value instanceof Scenario) {
            lastLoadedScenario = (Scenario) value;
            tabTextEdit.setContent(warningPane);
            return;
        } else {
            if (value instanceof Suite && value.contents != null) {
                switchCodeArea((Suite) value);
            } else {
                switchCodeArea(null);
            }
        }
        tabTextEdit.setContent(editorPane);
    }

    public void selTabChanged(ObservableValue<? extends Tab> observable, Tab oldValue, Tab newValue) {
        if (newValue == tabTextEdit && lastLoaded != null && lastLoaded instanceof Scenario) {
            cleanLastLoadedScenario = false;
            mainForm.keepTabSelection = true;
            mainForm.selectProgrammatically(lastLoaded.parent);
        }
        if (oldValue == tabTextEdit && !switchingSuite) {
            codeAreaProvider.getCodeArea().selectProgrammaticallyCurrentlyEditedScenario();
        }
        switchingSuite = false;
    }

    private void switchCodeArea(Suite suite) {
        VirtualizedScrollPane<SnowCodeArea> scrollPane;
        if (suite != null) {
            scrollPane = codeAreaProvider.getTextEditCodeArea(suite);
            scrollPane.getContent().moveCaretToCurrentlyEditedScenario(lastLoadedScenario);
        } else {
            scrollPane = codeAreaProvider.getNonEditableCodeAreaPane();
        }

        if (editorPane.getChildren().size() > 1) {
            editorPane.getChildren().remove(1);
        }
        editorPane.getChildren().add(scrollPane);
    }
}
