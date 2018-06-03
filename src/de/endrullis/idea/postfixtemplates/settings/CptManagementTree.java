package de.endrullis.idea.postfixtemplates.settings;

import com.intellij.ide.DataManager;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.ListPopup;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.*;
import com.intellij.util.Consumer;
import com.intellij.util.ObjectUtils;
import com.intellij.util.ui.UIUtil;
import com.intellij.util.ui.tree.TreeUtil;
import de.endrullis.idea.postfixtemplates.language.CptLang;
import de.endrullis.idea.postfixtemplates.language.CptUtil;
import de.endrullis.idea.postfixtemplates.languages.SupportedLanguages;
import de.endrullis.idea.postfixtemplates.utils.Tuple2;
import lombok.val;
import org.apache.commons.lang.ArrayUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.TreeSelectionListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.MutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

import static de.endrullis.idea.postfixtemplates.language.CptUtil.downloadFile;
import static de.endrullis.idea.postfixtemplates.utils.CollectionUtils.$;
import static de.endrullis.idea.postfixtemplates.utils.CollectionUtils._List;

public class CptManagementTree extends CheckboxTree implements Disposable {
	@NotNull
	private final DefaultTreeModel model;
	@NotNull
	private final CheckedTreeNode  root;

	private final boolean                             canAddFile = true;
	private String                                    lastFileId;
	private Tuple2<String, WebTemplateFile>           nextMissingWtf;
	private Iterator<Tuple2<String, WebTemplateFile>> missingWtfIter;

	CptManagementTree() {
		super(getRenderer(), new CheckedTreeNode(null));
		//canAddFile = ContainerUtil.find(providerToLanguage.keySet(), p -> StringUtil.isNotEmpty(p.getPresentableName())) != null;
		model = (DefaultTreeModel) getModel();
		root = (CheckedTreeNode) model.getRoot();

		TreeSelectionListener selectionListener = event -> selectionChanged();
		getSelectionModel().addTreeSelectionListener(selectionListener);
		Disposer.register(this, () -> getSelectionModel().removeTreeSelectionListener(selectionListener));
		DoubleClickListener doubleClickListener = new DoubleClickListener() {
			@Override
			protected boolean onDoubleClick(MouseEvent event) {
				TreePath location = getClosestPathForLocation(event.getX(), event.getY());
				return location != null && doubleClick(location.getLastPathComponent());
			}
		};
		doubleClickListener.installOn(this);
		Disposer.register(this, () -> doubleClickListener.uninstall(this));
		setRootVisible(false);
		setShowsRootHandles(true);
	}

	@Override
	protected void onDoubleClick(CheckedTreeNode node) {
		doubleClick(node);
	}

	private boolean doubleClick(@Nullable Object node) {
		if (node instanceof FileTreeNode && isEditable(((FileTreeNode) node).getFile())) {
			editFile((FileTreeNode) node);
			return true;
		}
		return false;
	}

	@Override
	public void dispose() {
		UIUtil.dispose(this);
	}

	@NotNull
	private static CheckboxTreeCellRenderer getRenderer() {
		return new CheckboxTreeCellRenderer() {
			@Override
			public void customizeRenderer(JTree tree, Object value, boolean selected, boolean expanded, boolean leaf, int row, boolean hasFocus) {
				if (!(value instanceof CheckedTreeNode)) return;
				CheckedTreeNode node = (CheckedTreeNode) value;

				final Color background = selected ? UIUtil.getTreeSelectionBackground() : UIUtil.getTreeTextBackground();
				FileTreeNode cptTreeNode = ObjectUtils.tryCast(node, FileTreeNode.class);
				SimpleTextAttributes attributes;
				if (cptTreeNode != null) {
					//Color fgColor = cptTreeNode.isChanged() || cptTreeNode.isNew() ? JBColor.BLUE : null;
					Color fgColor = cptTreeNode.getFile().hasChanged() ? JBColor.BLUE : null;
					attributes = new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, fgColor);
				} else {
					attributes = SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES;
				}
				getTextRenderer().append(StringUtil.notNullize(value.toString()),
					new SimpleTextAttributes(background, attributes.getFgColor(), JBColor.RED, attributes.getStyle()));

				if (cptTreeNode != null) {
					URL url = cptTreeNode.getFile().getUrl();
					if (url != null) {
						getTextRenderer().append("  " + url.toString(), new SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, JBColor.GRAY), false);
					}
				}
			}
		};
	}

	protected void selectionChanged() {

	}

	public void initTree(@NotNull Map<CptLang, List<CptPluginSettings.VFile>> lang2file) {
		root.removeAllChildren();

		val lang2webTemplateFiles = Arrays.stream(WebTemplateFileLoader.loadFromFs()).collect(Collectors.groupingBy(f -> f.lang));

		for (Map.Entry<CptLang, List<CptPluginSettings.VFile>> entry : lang2file.entrySet()) {
			val lang = entry.getKey();
			val vFiles = entry.getValue().stream().filter(f -> new File(f.file).exists()).collect(Collectors.toList());
			val langNode = findOrCreateLangNode(lang);
			val vFileIds = vFiles.stream().map(f -> f.id).collect(Collectors.toSet());
			val webTemplateFiles = lang2webTemplateFiles.getOrDefault(lang.getLanguage(), _List());
			val id2webTemplateFile = webTemplateFiles.stream().collect(Collectors.toMap(e -> e.id, e -> e));
			val previousId2missingWebTemplateFile = new ArrayList<Tuple2<String, WebTemplateFile>>();

			for (int i = 0; i < webTemplateFiles.size(); i++) {
				val f = webTemplateFiles.get(i);
				if (!vFileIds.contains(f.id)) {
					val previousId = i == 0 ? null : webTemplateFiles.get(i-1).id;
					previousId2missingWebTemplateFile.add($(previousId, f));
				}
			}
			missingWtfIter = previousId2missingWebTemplateFile.iterator();
			nextMissingWtf = missingWtfIter.hasNext() ? missingWtfIter.next() : null;

			lastFileId = null;

			tryAddingMissingWebTemplateFiles(lang, langNode);

			// add the other (old) nodes to the tree
			for (CptPluginSettings.VFile vFile : vFiles) {
				val webTemplateFile = id2webTemplateFile.get(vFile.id);

				// if the file has an ID which is no longer present in the web template files -> skip the file
				if (vFile.id != null && webTemplateFile == null) {
					continue;
				}

				URL url = null;
				try {
					url = vFile.id != null ? new URL(webTemplateFile.url) :
								vFile.url != null ? new URL(vFile.url) : null;
				} catch (MalformedURLException ignored) {
				}
				val cptFile = new CptVirtualFile(vFile.id, url, new File(vFile.file));
				lastFileId = vFile.id;

				val node = new FileTreeNode(lang, cptFile);
				node.setChecked(vFile.enabled);

				langNode.add(node);

				tryAddingMissingWebTemplateFiles(lang, langNode);
			}
		}

		model.nodeStructureChanged(root);
		TreeUtil.expandAll(this);
	}

	private void tryAddingMissingWebTemplateFiles(CptLang lang, DefaultMutableTreeNode langNode) {
		if (nextMissingWtf != null) {
			if (Objects.equals(nextMissingWtf._1, lastFileId)) {
				WebTemplateFile webTemplateFile = nextMissingWtf._2;
				try {
					CptVirtualFile cptFile = new CptVirtualFile(webTemplateFile.id, new URL(webTemplateFile.url), CptUtil.getTemplateFile(lang.getLanguage(), webTemplateFile.id), true);
					lastFileId = webTemplateFile.id;
					downloadFile(cptFile);

					FileTreeNode node = new FileTreeNode(lang, cptFile);
					node.setChecked(true);

					langNode.add(node);
				} catch (IOException ignored) {
				}
				nextMissingWtf = missingWtfIter.hasNext() ? missingWtfIter.next() : null;

				tryAddingMissingWebTemplateFiles(lang, langNode);
			}
		}
	}

	@Nullable
	public CptVirtualFile getSelectedFile() {
		TreePath path = getSelectionModel().getSelectionPath();
		return getFileFromPath(path);
	}

	@Nullable
	private static CptVirtualFile getFileFromPath(@Nullable TreePath path) {
		if (path == null || !(path.getLastPathComponent() instanceof FileTreeNode)) {
			return null;
		}
		return ((FileTreeNode) path.getLastPathComponent()).getFile();
	}

	public void selectFile(@NotNull final CptVirtualFile file) {
		visitFileNodes(node -> {
			if (file.equals(node.getFile())) {
				TreeUtil.selectInTree(node, true, this, true);
			}
		});
	}

	private void visitFileNodes(@NotNull Consumer<FileTreeNode> consumer) {
		Enumeration languages = root.children();
		while (languages.hasMoreElements()) {
			CheckedTreeNode langNode = (CheckedTreeNode) languages.nextElement();
			Enumeration fileNodes = langNode.children();
			while (fileNodes.hasMoreElements()) {
				Object fileNode = fileNodes.nextElement();
				if (fileNode instanceof FileTreeNode) {
					consumer.consume((FileTreeNode) fileNode);
				}
			}
		}
	}

	public boolean canAddFile() {
		return canAddFile;
	}

	public void addFile(@NotNull AnActionButton button) {
		val group = new DefaultActionGroup() {{
			for (CptLang lang : SupportedLanguages.supportedLanguages) {
				add(new DumbAwareAction(lang.getNiceName()) {
					@Override
					public void actionPerformed(AnActionEvent anActionEvent) {
						val project = CptUtil.getActiveProject();
						openFileEditDialog(project, lang, null);
					}
				});
			}
		}};

		/*
		for (Map.Entry<CptLang, String> entry : myProviderToLanguage.entrySet()) {
			CptLang provider = entry.getKey();
			String providerName = provider.getPresentableName();
			if (StringUtil.isEmpty(providerName)) continue;
			group.add(new DumbAwareAction(providerName) {
				@Override
				public void actionPerformed(AnActionEvent e) {
					PostfixTemplateEditor editor = provider.createEditor(null);
					if (editor != null) {
						PostfixEditTemplateDialog dialog = new PostfixEditTemplateDialog(com.intellij.codeInsight.template.postfix.settings.PostfixTemplatesCheckboxTree.this, editor, providerName, null);
						if (dialog.showAndGet()) {
							String templateKey = dialog.getTemplateName();
							String templateId = PostfixTemplatesUtils.generateTemplateId(templateKey, provider);
							CptVirtualFile createdTemplate = editor.createTemplate(templateId, templateKey);

							CptTreeNode createdNode = new CptTreeNode(createdTemplate, provider, true);
							DefaultMutableTreeNode languageNode = findOrCreateLangNode(entry.getValue());
							languageNode.add(createdNode);
							myModel.nodeStructureChanged(languageNode);
							TreeUtil.selectNode(com.intellij.codeInsight.template.postfix.settings.PostfixTemplatesCheckboxTree.this, createdNode);
						}
					}
				}
			});
		}
		*/

		DataContext context = DataManager.getInstance().getDataContext(button.getContextComponent());
		ListPopup popup = JBPopupFactory.getInstance().createActionGroupPopup(null, group, context,
			JBPopupFactory.ActionSelectionAid.ALPHA_NUMBERING, true, null);
		popup.show(ObjectUtils.assertNotNull(button.getPreferredPopupPoint()));
	}

	public void openFileEditDialog(Project project, CptLang lang, FileTreeNode fileNode) {
		val addNewNode = fileNode == null;
		val cptVirtualFile = addNewNode ? null : fileNode.getFile();
		val dialog = new AddTemplateFileDialog(project, lang, cptVirtualFile, getOtherFileNames(lang, fileNode));
		dialog.show();

		if (dialog.isOK()) {
			val newCptVirtualFile = dialog.getCptVirtualFile();

			// are we adding a new node?
			if (addNewNode) {
				val langNode = findOrCreateLangNode(lang);
				val newNode = new FileTreeNode(lang, newCptVirtualFile);

				langNode.add(newNode);
				model.nodeStructureChanged(langNode);
				TreeUtil.selectNode(this, newNode);
			} else {
				fileNode.setFile(newCptVirtualFile);
			}
		}
	}

	private Set<String> getOtherFileNames(CptLang lang, FileTreeNode fileNode) {
		val langNode = findOrCreateLangNode(lang);

		return TreeUtil.nodeChildren(langNode)
			.filter(n -> n != fileNode)
			.map(n -> ((FileTreeNode) n).getFile().getName().replace(".postfixTemplates", ""))
			.toSet();
	}

	public boolean canEditSelectedFile() {
		TreePath[] selectionPaths = getSelectionModel().getSelectionPaths();
		return (selectionPaths == null || selectionPaths.length <= 1) && isEditable(getSelectedFile());
	}

	public void editSelectedFile() {
		TreePath path = getSelectionModel().getSelectionPath();
		Object lastPathComponent = path.getLastPathComponent();
		if (lastPathComponent instanceof FileTreeNode) {
			editFile((FileTreeNode) lastPathComponent);
		}
	}

	private void editFile(@NotNull FileTreeNode fileNode) {
		val file = fileNode.getFile();

		if (isEditable(file)) {
			val project = CptUtil.getActiveProject();
			openFileEditDialog(project, fileNode.getLang(), fileNode);

			model.nodeChanged(fileNode);

			/*
			PostfixTemplateEditor editor = lang.createEditor(fileToEdit);
			if (editor == null) {
				editor = new DefaultPostfixTemplateEditor(lang, fileToEdit);
			}
			String providerName = StringUtil.notNullize(lang.getPresentableName());
			PostfixEditTemplateDialog dialog = new PostfixEditTemplateDialog(this, editor, providerName, fileToEdit);
			if (dialog.showAndGet()) {
				CptVirtualFile newTemplate = editor.createTemplate(file.getId(), dialog.getTemplateName());
				if (newTemplate.equals(file)) {
					return;
				}
				if (file.isBuiltin()) {
					CptVirtualFile builtin = file instanceof PostfixChangedBuiltinTemplate
						? ((PostfixChangedBuiltinTemplate) file).getBuiltinTemplate()
						: fileToEdit;
					fileNode.setTemplate(new PostfixChangedBuiltinTemplate(newTemplate, builtin));
				} else {
					fileNode.setTemplate(newTemplate);
				}
				myModel.nodeStructureChanged(fileNode);
			}
			*/
		}
	}

	public boolean canRemoveSelectedFiles() {
		TreePath[] paths = getSelectionModel().getSelectionPaths();
		if (paths == null) {
			return false;
		}
		for (TreePath path : paths) {
			CptVirtualFile file = getFileFromPath(path);
			if (isEditable(file)) {
				return true;
			}
		}
		return false;
	}

	public void removeSelectedFiles() {
		TreePath[] paths = getSelectionModel().getSelectionPaths();
		if (paths == null) {
			return;
		}
		for (TreePath path : paths) {
			FileTreeNode lastPathComponent = ObjectUtils.tryCast(path.getLastPathComponent(), FileTreeNode.class);

			if (lastPathComponent == null) continue;

			/*
			val checkBox = new JCheckBox("Delete file from filesystem");
			val dialog = new DialogWrapper(this, false) {
				{
					setTitle("Remove file?");
					init();
				}

				@Override
				protected JComponent createCenterPanel() {
					return checkBox;
				}

				@Override
				public JComponent getPreferredFocusedComponent() {
					return checkBox;
				}
			};
			dialog.show();

			if (dialog.getExitCode() == DialogWrapper.OK_EXIT_CODE) {
				if (checkBox.isSelected()) {
					CptVirtualFile file = lastPathComponent.getFile();
					file.getFile().delete();
				}
				TreeUtil.removeLastPathComponent(this, path);
			}
			*/
			
			TreeUtil.removeLastPathComponent(this, path);
		}
	}

	public void moveDownSelectedFiles() {
		moveSelectedNodes(1);
	}

	public void moveUpSelectedFiles() {
		moveSelectedNodes(-1);
	}

	private void moveSelectedNodes(int direction) {
		val paths = getSelectionModel().getSelectionPaths();
		if (paths == null || !canMoveSelectedFiles()) {
			return;
		}
		val sortedPaths = Arrays.stream(paths).sorted(Comparator.comparing(path -> {
			val fileNode = (MutableTreeNode) path.getLastPathComponent();
			val parentNode = (MutableTreeNode) path.getParentPath().getLastPathComponent();

			return model.getIndexOfChild(parentNode, fileNode);
		})).toArray(i -> new TreePath[i]);

		if (direction > 0) {
			ArrayUtils.reverse(sortedPaths);
		}

		for (TreePath path : sortedPaths) {
			val fileNode = (MutableTreeNode) path.getLastPathComponent();
			val parentNode = (MutableTreeNode) path.getParentPath().getLastPathComponent();

			if (getModel() instanceof DefaultTreeModel) {
				val model = (DefaultTreeModel) getModel();
				val index = model.getIndexOfChild(parentNode, fileNode) + direction;

				if (index >= 0 && index < model.getChildCount(parentNode)) {
					TreeUtil.removeLastPathComponent(this, path);
					model.insertNodeInto(fileNode, parentNode, index);
				}
			}
		}

		getSelectionModel().setSelectionPaths(paths);
		selectionChanged();
	}

	public boolean canMoveSelectedFiles() {
		val paths = getSelectionPaths();
		if (paths == null) {
			return false;
		}
		if (Arrays.stream(paths).allMatch(path -> path.getLastPathComponent() instanceof FileTreeNode)) {
			// ensure all file nodes have the same parent
			return Arrays.stream(paths).map(path -> path.getParentPath().getLastPathComponent()).collect(Collectors.toSet()).size() == 1;
		}
		return false;
	}

	private static boolean isEditable(@Nullable CptVirtualFile file) {
		return file != null && file.getId() == null;
	}

	@NotNull
	private DefaultMutableTreeNode findOrCreateLangNode(CptLang lang) {
		DefaultMutableTreeNode find = TreeUtil.findNode(root, n ->
			n instanceof LangTreeNode && lang.equals(((LangTreeNode) n).getLang()));

		if (find != null) {
			return find;
		}

		CheckedTreeNode languageNode = new LangTreeNode(lang);
		root.add(languageNode);

		return languageNode;
	}

	@NotNull
	HashMap<CptLang, List<CptVirtualFile>> getState() {
		HashMap<CptLang, List<CptVirtualFile>> state = new HashMap<>();

		visitFileNodes(n -> {
			state.computeIfAbsent(n.getLang(), e -> new ArrayList<>()).add(n.getFile());
		});

		return state;
	}

	@NotNull
	HashMap<String, List<CptPluginSettings.VFile>> getExport() {
		HashMap<String, List<CptPluginSettings.VFile>> export = new HashMap<>();

		val templatesPath = CptUtil.getTemplatesPath().getAbsolutePath();

		visitFileNodes(n -> {
			val url = n.getFile().getUrl() != null ? n.getFile().getUrl().toString() : null;
			val filePath = n.getFile().getFile().getAbsolutePath().replace(templatesPath, "${PLUGIN}");
			val vFile = new CptPluginSettings.VFile(n.isChecked(), n.getFile().getId(), url, filePath);
			export.computeIfAbsent(n.getLang().getLanguage(), e -> new ArrayList<>()).add(vFile);
		});

		return export;
	}

	private static class LangTreeNode extends CheckedTreeNode {
		@NotNull
		private final CptLang lang;

		LangTreeNode(@NotNull CptLang lang) {
			super(lang.getNiceName());
			this.lang = lang;
		}

		@NotNull
		public CptLang getLang() {
			return lang;
		}
	}
}
