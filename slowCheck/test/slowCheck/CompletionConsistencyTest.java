package slowCheck;

import com.intellij.codeInsight.completion.CodeCompletionHandlerBase;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupEx;
import com.intellij.codeInsight.lookup.LookupManager;
import com.intellij.codeInsight.lookup.impl.LookupImpl;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.SystemProperties;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.ui.UIUtil;

import java.util.List;

/**
 * @author peter
 */
public class CompletionConsistencyTest extends AbstractApplyAndRevertTestCase {
  
  static class CompletionInvocation extends ActionOnRange {
    final int itemIndexRaw;
    LookupElement selectedItem;
    final char completionChar;

    CompletionInvocation(Document document, int offset, int itemIndexRaw, char completionChar) {
      super(document, offset, offset);
      this.itemIndexRaw = itemIndexRaw;
      this.completionChar = completionChar;
    }

    @Override
    public String toString() {
      return "CompletionInvocation{" +
             "offset=" + getStartOffset() +
             ", selectedItem=" + selectedItem + "(" + itemIndexRaw + " )" +
             ", completionChar=" + StringUtil.escapeStringCharacters(String.valueOf(completionChar)) +
             '}';
    }
  }

  public void testCompletionConsistency() {
    Registry.get("ide.completion.variant.limit").setValue(100_000, getTestRootDisposable());
    CheckerSettings settings = CheckerSettings.DEFAULT_SETTINGS;
    Checker.forAll(settings.withIterationCount(20), javaFiles(), file -> {
      System.out.println("for file: " + file.getPresentableUrl());
      PsiFile psiFile = PsiManager.getInstance(myProject).findFile(file);
      if (psiFile == null) return false;
      int textLength = psiFile.getTextLength();

      Editor editor = FileEditorManager.getInstance(myProject).openTextEditor(new OpenFileDescriptor(myProject, file, 0), true);
      Document document = editor.getDocument();

      Generator<CompletionInvocation> genInvocation = Generator.from(data -> {
        int offset = data.drawInt(IntDistribution.uniform(0, textLength));
        int itemIndex = data.drawInt(IntDistribution.uniform(0, 100));
        char c = Generator.oneOf('\n', '\t', '\r', ' ', '.', '(').generateUnstructured(data);
        return new CompletionInvocation(document, offset, itemIndex, c);
      });
      Checker.forAll(settings.withIterationCount(10), Generator.listOf(genInvocation), list -> {
        changeDocumentAndRevert(document, () -> {
          for (int i = 0; i < list.size(); i++) {
            PsiDocumentManager.getInstance(myProject).commitAllDocuments();
            CompletionInvocation invocation = list.get(i);
            int offset = invocation.getStartOffset();
            if (offset < 0) continue;

            editor.getCaretModel().moveToOffset(offset);
            
            PsiElement leaf = psiFile.findElementAt(offset);

            try {
              performCompletion(editor, i == 0, invocation, leaf);
              checkStubPsiWellFormed(psiFile);
            }
            finally {
              LookupManager.getInstance(myProject).hideActiveLookup();
              UIUtil.dispatchAllInvocationEvents();
            }
          }
        });
        return true;
      });
      return true;
    });
  }

  private void performCompletion(Editor editor, boolean onValidCode, CompletionInvocation invocation, PsiElement leaf) {
    new CodeCompletionHandlerBase(CompletionType.BASIC).invokeCompletion(myProject, editor);

    boolean canExpectMismatch = !onValidCode ||
                                !(leaf instanceof PsiIdentifier) ||
                                !(leaf.getParent() instanceof PsiJavaCodeReferenceElement) ||
                                PsiTreeUtil.getParentOfType(leaf, PsiPackageStatement.class) != null ||
                                isInnermostReferenceQualifier(leaf);


    LookupEx lookup = LookupManager.getActiveLookup(editor);
    if (lookup == null) {
      if (canExpectMismatch) return;
      fail("No lookup");
    }

    List<LookupElement> items = lookup.getItems();
    LookupElement sameItem = ContainerUtil.find(items, e -> e.getAllLookupStrings().contains(leaf.getText()));
    if (sameItem == null && !canExpectMismatch) {
      fail("No variant " + leaf.getText() + " among " + items);
    }
    LookupElement item = items.get(invocation.itemIndexRaw % items.size());
    invocation.selectedItem = item;
    ((LookupImpl)lookup).finishLookup(invocation.completionChar, item);
  }

  private static boolean isInnermostReferenceQualifier(PsiElement leaf) {
    PsiElement parent = leaf.getParent();
    return parent instanceof PsiJavaCodeReferenceElement && 
           !((PsiJavaCodeReferenceElement)parent).isQualified() && 
           parent.getParent() instanceof PsiJavaCodeReferenceElement;
  }

  @Override
  protected String getTestDataPath() {
    return SystemProperties.getUserHome() + "/IdeaProjects/univocity-parsers";
  }
}
