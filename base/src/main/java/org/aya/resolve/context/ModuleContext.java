// Copyright (c) 2020-2022 Tesla (Yinsen) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
package org.aya.resolve.context;

import kala.collection.Map;
import kala.collection.immutable.ImmutableSeq;
import kala.collection.mutable.MutableMap;
import org.aya.concrete.stmt.QualifiedID;
import org.aya.concrete.stmt.Stmt;
import org.aya.concrete.stmt.UseHide;
import org.aya.generic.Constants;
import org.aya.ref.AnyVar;
import org.aya.ref.DefVar;
import org.aya.resolve.error.NameProblem;
import org.aya.util.error.SourcePos;
import org.aya.util.error.WithPos;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * @author re-xyr
 */
public sealed interface ModuleContext extends ModuleLikeContext permits NoExportContext, PhysicalModuleContext {
  /**
   * All available symbols in this context<br>
   * {@code Unqualified -> (Module Name -> TopLevel)}<br>
   * It says an {@link ContextUnit.TopLevel} can be referred by {@code {Module Name}::{Unqualified}}
   */
  @Override @NotNull MutableModuleSymbol<ContextUnit.TopLevel> symbols();

  /**
   * All imported modules in this context.<br/>
   * {@code Qualified Module -> Module Export}
   *
   * @apiNote empty list => this module
   * @implNote This module should be automatically imported.
   */
  @Override @NotNull MutableMap<ModulePath, MutableModuleExport> modules();

  /**
   * Import the whole module (including itself and re-exports)
   *
   * @see ModuleContext#importModule(ModulePath.Qualified, MutableModuleExport, Stmt.Accessibility, SourcePos)
   */
  default void importModules(
    @NotNull ModulePath.Qualified modName,
    @NotNull Map<ModulePath, MutableModuleExport> module,
    @NotNull Stmt.Accessibility accessibility,
    @NotNull SourcePos sourcePos
  ) {
    module.forEach((name, mod) -> importModule(modName.concat(name), mod, accessibility, sourcePos));
  }

  /**
   * Importing one module export.
   *
   * @param accessibility of importing, re-export if public
   * @param componentName the name of the module
   * @param moduleExport  the module
   */
  default void importModule(
    @NotNull ModulePath.Qualified componentName,
    @NotNull MutableModuleExport moduleExport,
    @NotNull Stmt.Accessibility accessibility,
    @NotNull SourcePos sourcePos
  ) {
    var modules = modules();
    if (modules.containsKey(componentName)) {
      reportAndThrow(new NameProblem.DuplicateModNameError(componentName.toImmutableSeq(), sourcePos));
    }
    if (getModuleMaybe(componentName) != null) {
      reporter().report(new NameProblem.ModShadowingWarn(componentName.toImmutableSeq(), sourcePos));
    }
    modules.set(componentName, moduleExport);
  }

  /**
   * Open an imported module
   *
   * @param modName the name of the module
   * @param filter  use or hide which definitions
   * @param rename  renaming
   */
  default void openModule(
    @NotNull ModulePath.Qualified modName,
    @NotNull Stmt.Accessibility accessibility,
    @NotNull ImmutableSeq<QualifiedID> filter,
    @NotNull ImmutableSeq<WithPos<UseHide.Rename>> rename,
    @NotNull SourcePos sourcePos,
    UseHide.Strategy strategy
  ) {
    var modExport = getModuleMaybe(modName);
    if (modExport == null) reportAndThrow(new NameProblem.ModNameNotFoundError(modName, sourcePos));

    var filterRes = modExport.filter(filter, strategy);
    if (filterRes.anyError()) reportAllAndThrow(filterRes.problems(modName));

    var filtered = filterRes.result();
    var mapRes = filtered.map(rename);
    if (mapRes.anyError()) reportAllAndThrow(mapRes.problems(modName));

    var renamed = mapRes.result();
    renamed.symbols().forEach((name, candidates) -> {
      candidates.forEach((componentName, ref) -> {
        var fullComponentName = modName.concat(componentName);
        var symbol = new GlobalSymbol.Imported(fullComponentName, name, ref, accessibility);
        addGlobal(symbol, sourcePos);
      });
    });

    // report all warning
    reportAll(filterRes.problems(modName).concat(mapRes.problems(modName)));
  }

  default void define(@NotNull ContextUnit.Defined defined, @NotNull SourcePos sourcePos) {
    addGlobal(new GlobalSymbol.Defined(defined.data().name(), defined), sourcePos);
  }

  /**
   * Adding a new symbol to this module.
   */
  default void addGlobal(
    @NotNull ModuleContext.GlobalSymbol symbol,
    @NotNull SourcePos sourcePos
  ) {
    var modName = symbol.componentName();
    var name = symbol.unqualifiedName();
    var symbols = symbols();
    if (!symbols.contains(name)) {
      if (getUnqualifiedMaybe(name, null, sourcePos) != null && !name.startsWith(Constants.ANONYMOUS_PREFIX)) {
        reporter().report(new NameProblem.ShadowingWarn(name, sourcePos));
      }
    } else if (symbols.containsDefinitely(modName, name)) {
      reportAndThrow(new NameProblem.DuplicateNameError(name, symbol.data().data(), sourcePos));
    } else {
      reporter().report(new NameProblem.AmbiguousNameWarn(name, sourcePos));
    }

    switch (symbol) {
      case GlobalSymbol.Defined defined -> {
        // Defined, not imported.
        var result = symbols.add(ModulePath.This, name, defined.data());
        assert result.isEmpty() : "Sanity check";
        doDefine(name, defined.data().data(), sourcePos);
      }
      case GlobalSymbol.Imported imported -> {
        var result = symbols.add(modName, name, imported.data());
        assert result.isEmpty() : "Sanity check";
      }
    }

    var exportSymbol = symbol.exportMaybe();
    if (exportSymbol != null) {
      doExport(modName, name, exportSymbol, sourcePos);
    }
  }

  default void doDefine(@NotNull String name, @NotNull AnyVar ref, @NotNull SourcePos sourcePos) {
    if (ref instanceof DefVar<?, ?> defVar) {
      var thisModule = modules().get(ModulePath.This);
      var success = thisModule.export(ModulePath.This, name, defVar);

      if (!success) {
        reportAndThrow(new NameProblem.DuplicateNameError(name, ref, sourcePos));
      }
    }
  }

  /**
   * Exporting an {@link AnyVar} with qualified id {@code {componentName}::{name}}
   */
  void doExport(@NotNull ModulePath componentName, @NotNull String name, @NotNull DefVar<?, ?> ref, @NotNull SourcePos sourcePos);

  // TODO: This is only used in ModuleContext#addGlobal
  sealed interface GlobalSymbol {
    @NotNull ContextUnit.TopLevel data();
    @NotNull ModulePath componentName();
    @NotNull String unqualifiedName();

    /**
     * @return null if not visible to outside
     */
    @Nullable DefVar<?, ?> exportMaybe();

    record Defined(
      @NotNull String unqualifiedName,
      @NotNull ContextUnit.Defined data
    ) implements GlobalSymbol {
      @Override
      public @NotNull ModulePath.This componentName() {
        return ModulePath.This;
      }

      @Override
      public @Nullable DefVar<?, ?> exportMaybe() {
        if (data instanceof ContextUnit.Exportable exportable && exportable.accessibility() == Stmt.Accessibility.Public) {
          return exportable.data();
        } else {
          return null;
        }
      }
    }

    record Imported(
      @NotNull ModulePath.Qualified componentName,
      @NotNull String unqualifiedName,
      @NotNull ContextUnit.Outside data,
      @NotNull Stmt.Accessibility accessibility
    ) implements GlobalSymbol {
      @Override
      public @Nullable DefVar<?, ?> exportMaybe() {
        if (accessibility == Stmt.Accessibility.Public) {
          return data.data();
        } else {
          return null;
        }
      }
    }
  }
}
