package cz.hudecekpetr.snowride.lexer;

import cz.hudecekpetr.snowride.fx.IAutocompleteOption;
import cz.hudecekpetr.snowride.semantics.IKnownKeyword;
import cz.hudecekpetr.snowride.semantics.codecompletion.TestCaseSettingOption;
import cz.hudecekpetr.snowride.tree.Suite;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Cell {

    public final String contents;
    public String postTrivia;
    public LogicalLine partOfLine;
    public boolean virtual;
    private boolean isComment;
    private boolean isKeyword;
    private int cellIndex;
    private List<IKnownKeyword> permissibleKeywords;

    public Cell(String contents, String postTrivia, LogicalLine partOfLine) {
        this.contents = contents;
        this.postTrivia = postTrivia;
        this.partOfLine = partOfLine;
    }

    @Override
    public String toString() {
        return contents;
    }


    public String getStyle() {
        updateSemanticsStatus();
        String style = "";
        if (cellIndex == 1 && (contents.startsWith("[") && contents.endsWith("]"))) {
            style += "-fx-text-fill: darkmagenta; ";
            style += "-fx-font-weight: bold; ";
        } else if (isComment) {
            style += "-fx-text-fill: brown; ";
        } else if (isKeyword) {
            style += "-fx-font-weight: bold; ";
            Optional<IKnownKeyword> first = permissibleKeywords.stream().filter(kk -> kk.getAutocompleteText().toLowerCase().equals(this.contents.toLowerCase())).findFirst();
            if (first.isPresent()) {
                if (first.get().getScenarioIfPossible() != null) {
                    style += "-fx-text-fill: blue; ";
                } else {
                    style += "-fx-text-fill: dodgerblue; ";
                }
            }
        } else if (contents.contains("${") || contents.contains("@{") || contents.contains("&{")) {
            style += "-fx-text-fill: green; ";
        }
        return style;
    }

    private void updateSemanticsStatus() {
        isComment = false;
        cellIndex = partOfLine.cells.indexOf(this);
        boolean pastTheKeyword = false;
        isKeyword = false;
        boolean skipFirst = true;
        for (Cell cell : partOfLine.cells) {
            if (skipFirst) {
                skipFirst = false;
                continue;
            }
            isKeyword = false;
            if (cell.contents.startsWith("#")) {
                isComment = true;
                break;
            }
            if (cell.contents.startsWith("${") || cell.contents.startsWith("@{") || cell.contents.startsWith("&{")) {
                // Is the return value.
            } else if (!pastTheKeyword) {
                // This is the keyword.
                isKeyword = true;
                permissibleKeywords = ((Suite) partOfLine.belongsToHighElement.parent).getKeywordsPermissibleInSuite().collect(Collectors.toList());
                pastTheKeyword = true;
            }
            if (cell == this) {
                // The rest doesn't matter.
                break;
            }
        }
    }

    public Stream<IAutocompleteOption> getCompletionOptions() {
        updateSemanticsStatus();
        Stream<IAutocompleteOption> options = Stream.empty();
        if (cellIndex == 1) {
            options = Stream.concat(options, TestCaseSettingOption.allOptions.stream());
        }
        if (isKeyword) {
            options = Stream.concat(options, permissibleKeywords.stream());
        }
        return options;
    }

    public IKnownKeyword getKeywordInThisCell() {
        updateSemanticsStatus();
        if (isKeyword) {
            Optional<IKnownKeyword> first = permissibleKeywords.stream()
                    .filter(kk -> kk.getAutocompleteText().toLowerCase().equals(this.contents.toLowerCase()))
                    .findFirst();
            if (first.isPresent()) {
                return first.get();
            }
        }
        return null;
    }
}
