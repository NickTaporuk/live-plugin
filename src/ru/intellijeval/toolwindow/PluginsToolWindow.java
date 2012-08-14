package ru.intellijeval.toolwindow;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.codeInsight.daemon.impl.analysis.FileHighlighingSetting;
import com.intellij.codeInsight.daemon.impl.analysis.HighlightLevelUtil;
import com.intellij.ide.ui.customization.CustomizationUtil;
import com.intellij.lang.Language;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileSystemTree;
import com.intellij.openapi.fileChooser.ex.FileChooserKeys;
import com.intellij.openapi.fileChooser.ex.FileNodeDescriptor;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.fileTypes.FileTypes;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.project.ProjectManagerListener;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowAnchor;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.pom.Navigatable;
import com.intellij.psi.FileViewProvider;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.util.PsiUtilBase;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.EditSourceOnDoubleClickHandler;
import com.intellij.util.EditSourceOnEnterKeyHandler;
import com.intellij.util.ui.tree.TreeUtil;
import org.jetbrains.annotations.NonNls;
import ru.intellijeval.EvalComponent;
import ru.intellijeval.EvaluateAction;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * User: dima
 * Date: 11/08/2012
 */
public class PluginsToolWindow {
	public static final ImageIcon ICON = new ImageIcon(PluginsToolWindow.class.getResource("/ru/intellijeval/toolwindow/plugin.png"));
	private static final String TOOL_WINDOW_ID = "Plugins";

	private FileSystemTree myFsTree;
	private SimpleToolWindowPanel panel;

	public PluginsToolWindow init() {
		ProjectManager.getInstance().addProjectManagerListener(new ProjectManagerListener() {
			@Override
			public void projectOpened(Project project) {
				registerWindowFor(project);
			}

			@Override
			public void projectClosed(Project project) {
				unregisterWindowFrom(project);
			}

			@Override
			public boolean canCloseProject(Project project) {
				return true;
			}

			@Override
			public void projectClosing(Project project) {
			}
		});
		return this;
	}

	private void registerWindowFor(Project project) {
		ToolWindowManager toolWindowManager = ToolWindowManager.getInstance(project);
		ToolWindow toolWindow = toolWindowManager.registerToolWindow(TOOL_WINDOW_ID, false, ToolWindowAnchor.RIGHT);
		toolWindow.setIcon(ICON);

		toolWindow.getContentManager().addContent(createContent(project));
	}

	private void unregisterWindowFrom(Project project) {
		ToolWindowManager.getInstance(project).unregisterToolWindow(TOOL_WINDOW_ID);
	}

	private Content createContent(Project project) {
		myFsTree = createFsTree(project);

		AnAction action = new NewElementPopupAction();
		action.registerCustomShortcutSet(
				new CustomShortcutSet(new KeyboardShortcut(KeyStroke.getKeyStroke("ctrl N"), null)), myFsTree.getTree()); // TODO use generic shortcut?
		CustomizationUtil.installPopupHandler(myFsTree.getTree(), "InetlliJEval.Popup", ActionPlaces.UNKNOWN);

		JScrollPane scrollPane = ScrollPaneFactory.createScrollPane(myFsTree.getTree());
		panel = new MySimpleToolWindowPanel(true, myFsTree).setProvideQuickActions(false);
		panel.add(scrollPane);

		panel.setToolbar(createToolBar());
		return ContentFactory.SERVICE.getInstance().createContent(panel, "", false);
	}

	private FileSystemTree createFsTree(Project project) {
		Ref<FileSystemTree> myFsTreeRef = Ref.create();
		MyTree myTree = new MyTree(project);
		// handlers must be installed before adding myTree to FileSystemTreeImpl
		EditSourceOnDoubleClickHandler.install(myTree, new DisableHighlightingRunnable(project, myFsTreeRef));
		EditSourceOnEnterKeyHandler.install(myTree, new DisableHighlightingRunnable(project, myFsTreeRef));

		FileSystemTree result = new PluginsFileSystemTree(project, myTree, null, null, null);
		myFsTreeRef.set(result);
		return result;
	}

	private void reloadPluginRoots(Project project) {
		myFsTree = createFsTree(project);
		JScrollPane scrollPane = ScrollPaneFactory.createScrollPane(myFsTree.getTree());
		panel.remove(0);
		panel.add(scrollPane, 0);
	}

	private JComponent createToolBar() {
		JPanel toolBarPanel = new JPanel(new GridLayout());
		DefaultActionGroup actionGroup = new DefaultActionGroup();

		actionGroup.add(new AddPluginAction(this));
		actionGroup.add(new RemovePluginAction(this, myFsTree));
		actionGroup.add(new EvaluateAction());
		// TODO expand / collapse (all) actions
		// TODO eval one plugin action

		toolBarPanel.add(ActionManager.getInstance().createActionToolbar(TOOL_WINDOW_ID, actionGroup, true).getComponent());
		return toolBarPanel;
	}

	private static class MySimpleToolWindowPanel extends SimpleToolWindowPanel {
		private final FileSystemTree fileSystemTree;

		public MySimpleToolWindowPanel(boolean vertical, FileSystemTree fileSystemTree) {
			super(vertical);
			this.fileSystemTree = fileSystemTree;
		}

		@Override public Object getData(@NonNls String dataId) {
			if (dataId.equals(FileSystemTree.DATA_KEY.getName())) return fileSystemTree;
			if (dataId.equals(FileChooserKeys.NEW_FILE_TYPE.getName())) return FileTypes.PLAIN_TEXT;
			if (dataId.equals(FileChooserKeys.DELETE_ACTION_AVAILABLE.getName())) return true;
			if (dataId.equals(PlatformDataKeys.VIRTUAL_FILE_ARRAY.getName())) return fileSystemTree.getSelectedFiles();
			return super.getData(dataId);
		}
	}

	private static class MyTree extends Tree implements TypeSafeDataProvider {
		private final Project project;

		private MyTree(Project project) {
			this.project = project;
		}

		@Override public void calcData(DataKey key, DataSink sink) {
			if (PlatformDataKeys.NAVIGATABLE_ARRAY.equals(key)) { // need this to be able to open files in toolwindow on double-click/enter
				List<FileNodeDescriptor> nodeDescriptors = TreeUtil.collectSelectedObjectsOfType(this, FileNodeDescriptor.class);
				List<Navigatable> navigatables = new ArrayList<Navigatable>();
				for (FileNodeDescriptor nodeDescriptor : nodeDescriptors) {
					navigatables.add(new OpenFileDescriptor(project, nodeDescriptor.getElement().getFile()));
				}
				sink.put(PlatformDataKeys.NAVIGATABLE_ARRAY, navigatables.toArray(new Navigatable[navigatables.size()]));
			}
		}
	}

	private static class AddPluginAction extends AnAction {
		private static final ImageIcon ICON = new ImageIcon(PluginsToolWindow.class.getResource("/ru/intellijeval/toolwindow/add.png"));
		private final PluginsToolWindow pluginsToolWindow;

		public AddPluginAction(PluginsToolWindow pluginsToolWindow) {
			super(ICON);
			this.pluginsToolWindow = pluginsToolWindow;
		}

		@Override public void actionPerformed(AnActionEvent event) {
			VirtualFile virtualFile = FileChooser.chooseFile(new FileChooserDescriptor(true, true, true, true, true, false), null, null);
			if (virtualFile == null) return;

			// TODO check that it's a valid plugin folder
			// TODO eval plugin?

			try {
				File fromDir = new File(virtualFile.getPath());
				File toDir = new File(EvalComponent.pluginsRootPath() + fromDir.getName());
				FileUtil.createDirectory(toDir);
				FileUtil.copyDirContent(fromDir, toDir);
			} catch (IOException e) {
				e.printStackTrace(); // TODO
			}

			pluginsToolWindow.reloadPluginRoots(event.getData(PlatformDataKeys.PROJECT));
		}
	}

	private static class RemovePluginAction extends AnAction {
		private static final ImageIcon ICON = new ImageIcon(PluginsToolWindow.class.getResource("/ru/intellijeval/toolwindow/remove.png"));
		private final PluginsToolWindow pluginsToolWindow;
		private final FileSystemTree fileSystemTree;

		private RemovePluginAction(PluginsToolWindow pluginsToolWindow, FileSystemTree fileSystemTree) {
			super(ICON);
			this.pluginsToolWindow = pluginsToolWindow;
			this.fileSystemTree = fileSystemTree;
		}

		@Override public void actionPerformed(AnActionEvent event) {
			// TODO confirmation dialog
			for (VirtualFile virtualFile : fileSystemTree.getSelectedFiles()) {
				String pluginPath = EvalComponent.pluginsRootPath() + "/" + virtualFile.getPath();
				FileUtil.delete(new File(pluginPath));
			}
			pluginsToolWindow.reloadPluginRoots(event.getData(PlatformDataKeys.PROJECT));
		}

		@Override public void update(AnActionEvent e) {
			Collection<String> pluginPaths = EvalComponent.pluginToPathMap().values();
			boolean anyPluginPathSelected = false;
			for (VirtualFile virtualFile : fileSystemTree.getSelectedFiles()) {
				if (pluginPaths.contains(virtualFile.getPath())) {
					anyPluginPathSelected = true;
					break;
				}
			}
			e.getPresentation().setEnabled(anyPluginPathSelected);
		}
	}

	private static class DisableHighlightingRunnable implements Runnable {
		private final Project project;
		private final Ref<FileSystemTree> myFsTree;

		public DisableHighlightingRunnable(Project project, Ref<FileSystemTree> myFsTree) {
			this.project = project;
			this.myFsTree = myFsTree;
		}

		@Override public void run() {
			VirtualFile selectedFile = myFsTree.get().getSelectedFile();
			if (selectedFile == null) return;

			PsiFile psiFile = PsiManager.getInstance(project).findFile(selectedFile);
			if (psiFile == null) return;

			disableHighlightingFor(psiFile);
		}

		private static void disableHighlightingFor(PsiFile psiFile) {
			FileViewProvider viewProvider = psiFile.getViewProvider();
			List<Language> languages = new ArrayList<Language>(viewProvider.getLanguages());
			Collections.sort(languages, PsiUtilBase.LANGUAGE_COMPARATOR);

			for (Language language : languages) {
				PsiElement root = viewProvider.getPsi(language);
				HighlightLevelUtil.forceRootHighlighting(root, FileHighlighingSetting.SKIP_HIGHLIGHTING);
			}
			DaemonCodeAnalyzer analyzer = DaemonCodeAnalyzer.getInstance(psiFile.getProject());
			analyzer.restart();
		}
	}
}
