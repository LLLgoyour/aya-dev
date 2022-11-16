// Copyright (c) 2020-2022 Tesla (Yinsen) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
package org.aya.pretty.backend.latex;

import kala.collection.Map;
import kala.tuple.Tuple;
import kala.tuple.Tuple2;
import org.aya.pretty.backend.string.Cursor;
import org.aya.pretty.backend.string.StringPrinter;
import org.aya.pretty.backend.string.StringPrinterConfig;
import org.aya.pretty.printer.ColorScheme;
import org.aya.pretty.printer.StyleFamily;
import org.aya.pretty.style.AyaColorScheme;
import org.aya.pretty.style.AyaStyleFamily;
import org.jetbrains.annotations.NotNull;

/**
 * @author ice1000
 */
public class DocTeXPrinter extends StringPrinter<DocTeXPrinter.Config> {
  @Override protected void renderHeader(@NotNull Cursor cursor) {
    cursor.invisibleContent("\\noindent");
  }

  @Override protected void renderPlainText(@NotNull Cursor cursor, @NotNull String content) {
    super.renderPlainText(cursor, content
      .replace("\\", "")
      .replace("_", "\\_"));
  }

  private static @NotNull Tuple2<String, String> id(@NotNull String name) {
    return Tuple.of(name, name);
  }
  private static final @NotNull Map<String, String> commandMapping = Map.ofEntries(
    Tuple.of("Pi", "\\Pi"),
    Tuple.of("Sig", "\\Sigma"),
    Tuple.of("\\", "\\lambda"),
    Tuple.of("\\/", "\\lor"),
    Tuple.of("/\\", "\\land"),
    Tuple.of("|", "\\mid"),
    Tuple.of("=>", "\\Rightarrow"),
    Tuple.of("->", "\\to"),
    Tuple.of("_|_", "\\bot"),
    Tuple.of("~", "\\neg"),
    Tuple.of("**", "\\times"),
    id(":"), id("."),
    id(":="),
    id("("), id(")"),
    id("[|"), id("|]"),
    Tuple.of("{", "\\{"),
    Tuple.of("}", "\\}")
  );

  @Override protected void renderSpecialSymbol(@NotNull Cursor cursor, @NotNull String text) {
    for (var k : commandMapping.keysView()) {
      if (text.equals(k)) {
        cursor.invisibleContent("$");
        cursor.visibleContent(commandMapping.get(k));
        cursor.invisibleContent("$");
        return;
      }
    }
    System.err.println("Warn: unknown symbol " + text);
    renderPlainText(cursor, text);
  }

  @Override public @NotNull String makeIndent(int indent) {
    if (indent == 0) return "";
    return "\\hspace*{" + indent * 0.5 + "em}";
  }

  @Override protected void renderHardLineBreak(@NotNull Cursor cursor) {
    cursor.lineBreakWith("\\\\\n");
  }

  /**
   * @author ice1000
   */
  public static class Config extends StringPrinterConfig {
    public Config() {
      this(AyaColorScheme.INTELLIJ, AyaStyleFamily.DEFAULT);
    }
    public Config(@NotNull ColorScheme colorScheme, @NotNull StyleFamily styleFamily) {
      super(new TeXStylist(colorScheme, styleFamily), INFINITE_SIZE, false);
    }
  }
}
