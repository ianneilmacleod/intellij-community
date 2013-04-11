package com.jetbrains.python.inspections;

import com.intellij.codeInspection.LocalInspectionToolSession;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.util.PsiTreeUtil;
import com.jetbrains.python.PyBundle;
import com.jetbrains.python.PyNames;
import com.jetbrains.python.PyTokenTypes;
import com.jetbrains.python.psi.*;
import com.jetbrains.python.psi.resolve.PyResolveContext;
import com.jetbrains.python.psi.types.*;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * Looks at argument lists.
 * @author dcheryasov
 */
public class PyArgumentListInspection extends PyInspection {
  @Nls
  @NotNull
  public String getDisplayName() {
    return PyBundle.message("INSP.NAME.incorrect.call.arguments");
  }

  @NotNull
  @Override
  public PsiElementVisitor buildVisitor(@NotNull final ProblemsHolder holder, final boolean isOnTheFly, @NotNull LocalInspectionToolSession session) {
    return new Visitor(holder, session);
  }

  public static class Visitor extends PyInspectionVisitor {

    public Visitor(final ProblemsHolder holder, LocalInspectionToolSession session) {
      super(holder, session);
    }

    @Override
    public void visitPyArgumentList(final PyArgumentList node) {
      // analyze
      inspectPyArgumentList(node, getHolder(), myTypeEvalContext);
    }

    @Override
    public void visitPyDecoratorList(final PyDecoratorList node) {
      PyDecorator[] decos = node.getDecorators();
      for (PyDecorator deco : decos) {
        if (! deco.hasArgumentList()) {
          // empty arglist; deco function must have a non-kwarg first arg
          PyCallExpression.PyMarkedCallee mkfunc = deco.resolveCallee(resolveWithoutImplicits());
          if (mkfunc != null && !mkfunc.isImplicitlyResolved()) {
            Callable callable = mkfunc.getCallable();
            int first_param_offset =  mkfunc.getImplicitOffset();
            PyParameter[] params = callable.getParameterList().getParameters();
            PyNamedParameter alleged_first_param = params.length < first_param_offset ? null : params[first_param_offset-1].getAsNamed();
            if (alleged_first_param == null || alleged_first_param.isKeywordContainer()) {
              // no parameters left to pass function implicitly, or wrong param type
              registerProblem(deco, PyBundle.message("INSP.func.$0.lacks.first.arg", callable.getName())); // TODO: better names for anon lambdas
            }
            else {
              // possible unfilled params
              for (int i=first_param_offset; i < params.length; i += 1) {
                PyNamedParameter par = params[i].getAsNamed();
                // param tuples, non-starred or non-default won't do
                if (par == null || (! par.isKeywordContainer() && ! par.isPositionalContainer() && !par.hasDefaultValue())) {
                  String par_name;
                  if (par != null) par_name = par.getName();
                  else par_name = "(...)"; // can't be bothered to find the first non-tuple inside it
                  registerProblem(deco, PyBundle.message("INSP.parameter.$0.unfilled", par_name));
                }
              }
            }
          }
        }
        // else: this case is handled by arglist visitor
      }
    }

  }

  public static void inspectPyArgumentList(PyArgumentList node, ProblemsHolder holder, final TypeEvalContext context, int implicitOffset) {
    if (node.getParent() instanceof PyClass) return; // class Foo(object) is also an arg list
    CallArgumentsMapping result = node.analyzeCall(PyResolveContext.noImplicits().withTypeEvalContext(context), implicitOffset);
    highlightIncorrectArguments(holder, result);
    highlightMissingArguments(node, holder, result);
    highlightStarArgumentTypeMismatch(node, holder, context);
  }

  public static void inspectPyArgumentList(PyArgumentList node, ProblemsHolder holder, final TypeEvalContext context) {
    inspectPyArgumentList(node, holder, context, 0);
  }

  private static void highlightIncorrectArguments(ProblemsHolder holder, CallArgumentsMapping result) {
    for (Map.Entry<PyExpression, EnumSet<CallArgumentsMapping.ArgFlag>> argEntry : result.getArgumentFlags().entrySet()) {
      EnumSet<CallArgumentsMapping.ArgFlag> flags = argEntry.getValue();
      if (!flags.isEmpty()) { // something's wrong
        PyExpression arg = argEntry.getKey();
        if (flags.contains(CallArgumentsMapping.ArgFlag.IS_DUP)) {
          holder.registerProblem(arg, PyBundle.message("INSP.duplicate.argument"));
        }
        if (flags.contains(CallArgumentsMapping.ArgFlag.IS_DUP_KWD)) {
          holder.registerProblem(arg, PyBundle.message("INSP.duplicate.doublestar.arg"));
        }
        if (flags.contains(CallArgumentsMapping.ArgFlag.IS_DUP_TUPLE)) {
          holder.registerProblem(arg, PyBundle.message("INSP.duplicate.star.arg"));
        }
        if (flags.contains(CallArgumentsMapping.ArgFlag.IS_POS_PAST_KWD)) {
          holder.registerProblem(arg, PyBundle.message("INSP.cannot.appear.past.keyword.arg"));
        }
        if (flags.contains(CallArgumentsMapping.ArgFlag.IS_UNMAPPED)) {
          holder.registerProblem(arg, PyBundle.message("INSP.unexpected.arg"));
        }
        if (flags.contains(CallArgumentsMapping.ArgFlag.IS_TOO_LONG)) {
          final PyCallExpression.PyMarkedCallee markedCallee = result.getMarkedCallee();
          String parameterName = null;
          if (markedCallee != null) {
            final PyParameter[] parameters = markedCallee.getCallable().getParameterList().getParameters();
            for (int i = parameters.length - 1; i >= 0; --i) {
              final PyParameter param = parameters[i];
              if (param instanceof PyNamedParameter) {
                final List<PyNamedParameter> unmappedParams = result.getUnmappedParams();
                if (!((PyNamedParameter)param).isPositionalContainer() && !((PyNamedParameter)param).isKeywordContainer() &&
                    param.getDefaultValue() == null && !unmappedParams.contains(param)) {
                  parameterName = param.getName();
                  break;
                }
              }
            }
            holder.registerProblem(arg, parameterName != null ? PyBundle.message("INSP.multiple.values.resolve.to.positional.$0", parameterName)
                                                              : PyBundle.message("INSP.more.args.that.pos.params"));
          }
        }
      }
    }
  }

  private static void highlightStarArgumentTypeMismatch(PyArgumentList node, ProblemsHolder holder, TypeEvalContext context) {
    for (PyExpression arg : node.getArguments()) {
      if (arg instanceof PyStarArgument) {
        PyExpression content = PyUtil.peelArgument(PsiTreeUtil.findChildOfType(arg, PyExpression.class));
        if (content != null) {
          PyType inside_type = context.getType(content);
          if (inside_type != null && !PyTypeChecker.isUnknown(inside_type)) {
            if (((PyStarArgument)arg).isKeyword()) {
              if (!PyABCUtil.isSubtype(inside_type, PyNames.MAPPING)) {
                holder.registerProblem(arg, PyBundle.message("INSP.expected.dict.got.$0", inside_type.getName()));
              }
            }
            else { // * arg
              if (!PyABCUtil.isSubtype(inside_type, PyNames.ITERABLE)) {
                holder.registerProblem(arg, PyBundle.message("INSP.expected.iter.got.$0", inside_type.getName()));
              }
            }
          }
        }
      }
    }
  }

  private static void highlightMissingArguments(PyArgumentList node, ProblemsHolder holder, CallArgumentsMapping result) {
    ASTNode our_node = node.getNode();
    if (our_node != null) {
      ASTNode close_paren = our_node.findChildByType(PyTokenTypes.RPAR);
      if (close_paren != null) {
        for (PyNamedParameter param : result.getUnmappedParams()) {
          holder.registerProblem(close_paren.getPsi(), PyBundle.message("INSP.parameter.$0.unfilled", param.getName()));
        }
      }
    }
  }
}
