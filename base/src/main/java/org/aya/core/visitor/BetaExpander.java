// Copyright (c) 2020-2022 Tesla (Yinsen) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
package org.aya.core.visitor;

import org.aya.core.term.*;
import org.aya.util.Arg;
import org.aya.guest0x0.cubical.Partial;
import org.aya.guest0x0.cubical.Restr;
import org.aya.ref.LocalVar;
import org.jetbrains.annotations.NotNull;

import java.util.function.Function;

/**
 * We think of all cubical reductions as beta reductions.
 *
 * @author wsx
 * @see DeltaExpander
 */
public interface BetaExpander extends EndoTerm {
  private static @NotNull Partial<Term> partial(@NotNull Partial<Term> partial) {
    return partial.flatMap(Function.identity());
  }
  @Override default @NotNull Term post(@NotNull Term term) {
    return switch (term) {
      case FormulaTerm mula -> mula.simpl();
      case PartialTyTerm ty -> ty.normalizeRestr();
      case MetaPatTerm metaPat -> metaPat.inline();
      case AppTerm app -> {
        var result = AppTerm.make(app);
        yield result == term ? result : apply(result);
      }
      case ProjTerm proj -> ProjTerm.proj(proj);
      case MatchTerm match -> {
        var result = match.tryMatch();
        yield result.isDefined() ? result.get() : match;
      }
      case PAppTerm(var of, var args, PathTerm.Cube(var xi, var type, var partial)) -> {
        if (of instanceof ErasedTerm) {
          var ui = args.map(Arg::term);
          yield new ErasedTerm(type.subst(new Subst(xi, ui)));
        }
        if (of instanceof PLamTerm lam) {
          var ui = args.map(Arg::term);
          var subst = new Subst(lam.params(), ui);
          yield apply(lam.body().subst(subst));
        }
        yield switch (partial(partial)) {
          case Partial.Split<Term> hap -> new PAppTerm(of, args, new PathTerm.Cube(xi, type, hap));
          case Partial.Const<Term> sad -> sad.u();
        };
      }
      case PartialTerm partial -> new PartialTerm(partial(partial.partial()), partial.rhsType());
      case CoeTerm coe -> {
        if (coe.restr() instanceof Restr.Const<Term> c && c.isOne()) {
          var var = new LocalVar("x");
          yield new LamTerm(coeDom(var, coe.type()), new RefTerm(var));
        }

        var varI = new LocalVar("i");
        var codom = apply(AppTerm.make(coe.type(), new Arg<>(new RefTerm(varI), true)));

        yield switch (codom) {
          case PathTerm path -> coe;
          case PiTerm pi -> pi.coe(coe, varI);
          case SigmaTerm sigma -> sigma.coe(coe, varI);
          case FormTerm.Type type -> {
            var A = new LocalVar("A");
            yield new LamTerm(new Term.Param(A, type, true), new RefTerm(A));
          }
          default -> coe;
        };
      }
      default -> term;
    };
  }
  @NotNull static Term.Param coeDom(LocalVar u0Var, Term type) {
    return new Term.Param(u0Var, AppTerm.make(type, new Arg<>(FormulaTerm.LEFT, true)), true);
  }
}
