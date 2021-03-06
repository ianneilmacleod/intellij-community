package slowCheck;

import com.intellij.codeInspection.ex.InspectionProfileImpl;
import com.intellij.ide.impl.ProjectUtil;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathMacros;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.application.ex.ApplicationEx;
import com.intellij.openapi.compiler.CompilerMessage;
import com.intellij.openapi.compiler.CompilerMessageCategory;
import com.intellij.openapi.diagnostic.DefaultLogger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.projectRoots.impl.JavaAwareProjectJdkTableImpl;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.impl.DebugUtil;
import com.intellij.psi.impl.source.PsiFileImpl;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.stubs.StubTree;
import com.intellij.testFramework.CompilerTester;
import com.intellij.testFramework.PlatformTestCase;
import com.intellij.testFramework.TestDataProvider;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public abstract class AbstractApplyAndRevertTestCase extends PlatformTestCase {
  protected CompilerTester myCompilerTester;
  protected Project myProject;

  @Override
  public Object getData(String dataId) {
    return myProject == null ? null : new TestDataProvider(myProject).getData(dataId);
  }

  protected Generator<VirtualFile> javaFiles() {
    GlobalSearchScope projectScope = GlobalSearchScope.projectScope(myProject);
    List<VirtualFile> allFiles = new ArrayList<>(FilenameIndex.getAllFilesByExt(myProject, "java", projectScope));
    return Generator.oneOf(allFiles);
  }

  protected void changeDocumentAndRevert(Document document, Runnable r) {
    CharSequence initialText = document.getImmutableCharSequence();
    try {
      r.run();
    }
    finally {
      WriteAction.run(() -> {
        PsiDocumentManager.getInstance(myProject).doPostponedOperationsAndUnblockDocument(document);
        document.setText(initialText);
        PsiDocumentManager.getInstance(myProject).commitAllDocuments();
        FileDocumentManager.getInstance().saveAllDocuments();
      });
    }

  }

  protected abstract String getTestDataPath();

  public void setUp() throws Exception {
    super.setUp();
    PathMacros.getInstance().setMacro("MAVEN_REPOSITORY", getDefaultMavenRepositoryPath());
    WriteAction.run(() -> {
      ProjectJdkTable.getInstance().addJdk(JavaAwareProjectJdkTableImpl.getInstanceEx().getInternalJdk());
      Application application = ApplicationManager.getApplication();
      ((ApplicationEx)application).doNotSave(false);
      application.saveAll();
    });

    myProject = ProjectUtil.openOrImport(getTestDataPath(), null, false);

    WriteAction.run(
      () -> ProjectRootManager.getInstance(myProject).setProjectSdk(JavaAwareProjectJdkTableImpl.getInstanceEx().getInternalJdk()));

    InspectionProfileImpl.INIT_INSPECTIONS = true;

    DefaultLogger.disableStderrDumping(getTestRootDisposable());
  }

  protected void initCompiler() {
    try {
      myCompilerTester = new CompilerTester(myProject, ContainerUtil.list(ModuleManager.getInstance(myProject).getModules()[0]));
      checkCompiles(myCompilerTester.rebuild());
    }
    catch (Throwable e) {
      fail(e.getMessage());
    }
  }

  protected String getDefaultMavenRepositoryPath() {
    final String root = System.getProperty("user.home", null);
    return (root != null ? new File(root, ".m2/repository") : new File(".m2/repository")).getAbsolutePath();
  }

  public void tearDown() throws Exception {
    try {
      if (myCompilerTester != null) {
        myCompilerTester.tearDown();
      }
   
      ProjectManager.getInstance().closeProject(myProject);
      WriteAction.run(() -> Disposer.dispose(myProject));
      
      myProject = null;
      InspectionProfileImpl.INIT_INSPECTIONS = false;
    }
    finally {
      super.tearDown();
    }
  }

  protected static void checkCompiles(List<CompilerMessage> messages) {
    List<CompilerMessage> compilerMessages = filterErrors(messages);
    if (!compilerMessages.isEmpty()) {
      fail(StringUtil.join(compilerMessages, mes -> mes.getMessage(), "\n"));
    }
  }

  protected static List<CompilerMessage> filterErrors(List<CompilerMessage> messages) {
    return messages.stream()
      .filter(message -> message.getCategory() == CompilerMessageCategory.ERROR)
      .collect(Collectors.toList());
  }

  protected void checkPsiWellFormed(PsiFile file) {
    PsiFile copy = PsiFileFactory.getInstance(myProject).createFileFromText(file.getName(), file.getLanguage(), file.getText());
    assertEquals(DebugUtil.psiTreeToString(copy, false), DebugUtil.psiTreeToString(file, false));

    Document document = file.getViewProvider().getDocument();
    if (!PsiDocumentManager.getInstance(myProject).isCommitted(document)) {
      PsiDocumentManager.getInstance(myProject).commitDocument(document);
      checkPsiWellFormed(file);
    }
  }

  protected static void checkStubPsiWellFormed(@NotNull PsiFile file) {
    Project project = file.getProject();

    StubTree tree = getStubTree(file);
    StubTree copyTree = getStubTree(
      PsiFileFactory.getInstance(project).createFileFromText(file.getName(), file.getLanguage(), file.getText()));
    if (tree == null || copyTree == null) return;

    assertEquals(DebugUtil.stubTreeToString(copyTree.getRoot()), DebugUtil.stubTreeToString(tree.getRoot()));

    Document document = file.getViewProvider().getDocument();
    assert document != null;
    if (!PsiDocumentManager.getInstance(project).isCommitted(document)) {
      PsiDocumentManager.getInstance(project).commitDocument(document);
      checkStubPsiWellFormed(file);
    }
  }

  @Nullable
  private static StubTree getStubTree(PsiFile file) {
    if (!(file instanceof PsiFileImpl)) return null;
    if (((PsiFileImpl)file).getElementTypeForStubBuilder() == null) return null;

    StubTree tree = ((PsiFileImpl)file).getStubTree();
    return tree != null ? tree : ((PsiFileImpl)file).calcStubTree();
  }

}
