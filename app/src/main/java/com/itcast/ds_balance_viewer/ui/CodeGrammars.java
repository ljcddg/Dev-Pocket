package com.itcast.ds_balance_viewer.ui;

import io.noties.prism4j.Prism4j;
import io.noties.prism4j.annotations.PrismBundle;

@PrismBundle(
    include = {"java", "kotlin", "python", "javascript", "json", "markup",
               "css", "sql", "c", "cpp", "go", "yaml", "csharp"},
    grammarLocatorClassName = ".ChatCodeGrammarLocator"
)
final class CodeGrammars {
    static Prism4j create() {
        return new Prism4j(new ChatCodeGrammarLocator());
    }
}
