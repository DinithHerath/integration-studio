/*
 * Copyright (c) 2015, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.wso2.developerstudio.eclipse.wso2plugin.sample.ui.wizard;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.net.URL;
import java.text.MessageFormat;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.MultiStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.ErrorDialog;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.wso2.developerstudio.eclipse.logging.core.IDeveloperStudioLog;
import org.wso2.developerstudio.eclipse.logging.core.Logger;
import org.wso2.developerstudio.eclipse.platform.core.project.model.ProjectWizardSettings;
import org.wso2.developerstudio.eclipse.platform.core.utils.DeveloperStudioProviderUtils;
import org.wso2.developerstudio.eclipse.platform.ui.utils.MessageDialogUtils;
import org.wso2.developerstudio.eclipse.platform.ui.wizard.AbstractWSO2ProjectCreationWizard;
import org.wso2.developerstudio.eclipse.platform.ui.wizard.pages.ProjectOptionsDataPage;
import org.wso2.developerstudio.eclipse.platform.ui.wizard.pages.ProjectOptionsPage;
import org.wso2.developerstudio.eclipse.utils.archive.ArchiveManipulator;
import org.wso2.developerstudio.eclipse.utils.data.ITemporaryFileTag;
import org.wso2.developerstudio.eclipse.utils.file.FileUtils;
import org.wso2.developerstudio.eclipse.wso2plugin.sample.Activator;
import org.wso2.developerstudio.eclipse.wso2plugin.sample.project.WSO2PluginProjectModel;
import org.wso2.developerstudio.eclipse.wso2plugin.sample.ui.elements.WSO2PluginSampleExt;
import org.wso2.developerstudio.eclipse.wso2plugin.sample.ui.elements.WSO2PluginSampleExtList;
import org.wso2.developerstudio.eclipse.wso2plugin.sample.util.WSO2PluginConstants;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonIOException;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

public class WSO2PluginProjectWizard extends AbstractWSO2ProjectCreationWizard {

	private WSO2PluginProjectModel wso2PluginProjectModel;
	private static IDeveloperStudioLog log = Logger.getLog(Activator.PLUGIN_ID);
	private ISelection selectedPlugin;
	private boolean isWizardSelected = false;
	boolean isProjectCreated = false;

	public WSO2PluginProjectWizard() {
		setWso2PluginProjectModel(new WSO2PluginProjectModel());
		setModel(wso2PluginProjectModel);
		setWindowTitle(WSO2PluginConstants.PROJECT_WIZARD_WINDOW_TITLE);
	}

	private void setWso2PluginProjectModel(WSO2PluginProjectModel wso2PluginProjectModel) {
		this.wso2PluginProjectModel = wso2PluginProjectModel;
	}

	@Override
	public void addPages() {
		URL resource = getWizardManifest();
		try {
			ProjectWizardSettings settings = new ProjectWizardSettings(resource.openStream(), configElement);

			if (settings.getProjectOptions().size() == 1) {
				getModel().setSelectedOption(settings.getProjectOptions().get(0).getId());
			} else {
				addPage(new ProjectOptionsPage(settings, getModel()));
			}
			addPage(new ProjectOptionsDataPage(settings, getModel(), getCurrentSelection(),
			                                   isRequireProjectLocationSection(), isRequiredWorkingSet(),
			                                   isRequiredWorkspaceLocation()));
			if (isCustomPageRequired()) {
				addPage(getCustomPage());
			}
			addPage(new WSO2PluginListSelectionPage(getAvailableWSO2Plugins(),
			                                        WSO2PluginConstants.PLUGIN_SELECT_WIZARD_PAGE_NAME, this));

		} catch (Exception e) {
			log.error("error adding pages", e);
		}

	}

	@Override
	public IResource getCreatedResource() {
		return null;
	}

	@Override
	public boolean performFinish() {
		Job projectCreationJob = new Job(WSO2PluginConstants.CREATE_PLUGIN_PROJECT_JOB) {
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				isProjectCreated = openSelectedProjectInWorkspace(selectedPlugin);
				return Status.OK_STATUS;
			}
		};
		projectCreationJob.schedule();
		return true;
	}

	public WSO2PluginSampleExtList getAvailableWSO2Plugins() {
		WSO2PluginSampleExtList elemList = new WSO2PluginSampleExtList();
		DeveloperStudioProviderUtils devStudioUtils = new DeveloperStudioProviderUtils();
		IConfigurationElement[] elements = devStudioUtils.getExtensionPointmembers(WSO2PluginConstants.EXTENSION_ID);
		for (int j = 0; j < elements.length; j++) {
			WSO2PluginSampleExt element = createWizardElement(elements[j]);
			if (element != null) {
				elemList.addWSO2Plugin(element);
			}
		}
		if (WSO2PluginListSelectionPage.isUpdateFromGit) {
			String fileName = WSO2PluginListSelectionPage.tempCloneDir + File.separator;
			WSO2PluginSampleExt[] pluginSampleExt =
			                                        addWizardElemsFromDownloadedPlugins(fileName +
			                                                                            WSO2PluginConstants.SAMPLE_DESCRIPTION_FILE);
			for (WSO2PluginSampleExt pluginSamle : pluginSampleExt) {
				String iconLocation = fileName + WSO2PluginConstants.ICONS + File.separator + pluginSamle.getIconLoc();
				pluginSamle.setIconLoc(iconLocation);
				String tempArchiveLoc = fileName + pluginSamle.getPluginArchive();
				pluginSamle.setPluginArchive(tempArchiveLoc);
				pluginSamle.setIsUpdatedFromGit(String.valueOf(true));
				elemList.add(pluginSamle);
			}
		}
		return elemList;
	}

	private WSO2PluginSampleExt[] addWizardElemsFromDownloadedPlugins(String fileName) {
		Gson gson = new Gson();
		JsonParser parser = new JsonParser();
		JsonElement jsonElement = null;
		try {
			jsonElement = parser.parse(new FileReader(fileName));
		} catch (JsonIOException | JsonSyntaxException | FileNotFoundException e1) {
			log.error("Could not read the json file containing the plugin template information " + e1);
			MultiStatus status =
			                     MessageDialogUtils.createMultiStatus(e1.getLocalizedMessage(), e1,
			                                                          WSO2PluginConstants.PACKAGE_ID);
			// show error dialog
			ErrorDialog.openError(this.getShell(), WSO2PluginConstants.ERROR_DIALOG_TITLE,
			                      "Could not read the json file containing the plugin template information. ", status);
		}
		WSO2PluginSampleExt[] pluginSampleExt = gson.fromJson(jsonElement, WSO2PluginSampleExt[].class);

		return pluginSampleExt;

	}

	protected WSO2PluginSampleExt createWizardElement(IConfigurationElement config) {
		String name = config.getAttribute(WSO2PluginConstants.PLUGIN_NAME);
		String archive = config.getAttribute(WSO2PluginConstants.PLUGIN_ARCHIVE_LOCATION);
		String description = config.getAttribute(WSO2PluginConstants.GET_PLUGIN_DESCRIPTION);
		String providerBundleID = config.getContributor().getName();
		String iconLoc = config.getAttribute(WSO2PluginConstants.ICON);
		if (name == null || archive == null || providerBundleID == null) {
			openSelectedProjectInWorkspace(selectedPlugin);
		}
		WSO2PluginSampleExt pluginElem =
		                                 new WSO2PluginSampleExt(name, archive, description, providerBundleID, iconLoc,
		                                                         String.valueOf(false));

		return pluginElem;
	}

	public boolean openSelectedProjectInWorkspace(ISelection iSelection) {

		iSelection.getClass();
		if (!(iSelection instanceof IStructuredSelection)) {
			return false;
		}
		IStructuredSelection structSelection = (IStructuredSelection) iSelection;
		WSO2PluginSampleExt element = (WSO2PluginSampleExt) structSelection.getFirstElement();
		String projectName = wso2PluginProjectModel.getPluginProjectName();
		String projectArchiveLoc = element.getPluginArchive();
		String pluginID = element.getBundleID();
		boolean isFromGit = Boolean.parseBoolean(element.getIsUpdatedFromGit());
		return openZipArchive(projectArchiveLoc, projectName, pluginID, isFromGit);

	}

	private boolean openZipArchive(String projectArchiveLoc, String projectName, String pluginBundleID,
	                               boolean isFromLocalFileSys) {
		ITemporaryFileTag sampleTempTag = FileUtils.createNewTempTag();
		IWorkspace workspace = ResourcesPlugin.getWorkspace();
		IProjectDescription newProjectDescription = workspace.newProjectDescription(projectName);
		IProject newProject = workspace.getRoot().getProject(projectName);
		try {
			newProject.create(newProjectDescription, null);

			newProject.open(null);
			URL zipLocationURL = null;
			if (isFromLocalFileSys) {
				zipLocationURL = new URL(WSO2PluginConstants.FILE_PROTOCOL + projectArchiveLoc);
			} else {
				zipLocationURL =
				                 FileLocator.find(Platform.getBundle(pluginBundleID), new Path(projectArchiveLoc), null);
			}
			File destinationFile = FileUtils.createTempFile();
			FileUtils.createFile(destinationFile, zipLocationURL.openStream());
			File resourceFile = destinationFile;

			File tempDir = FileUtils.createTempDirectory();
			File target = newProject.getLocation().toFile();
			File projectTempLocation = new File(tempDir, newProject.getName());

			ArchiveManipulator archiveManipulator = new ArchiveManipulator();
			archiveManipulator.extract(resourceFile, tempDir);
			File[] listFiles = tempDir.listFiles();
			String sampleName = listFiles[0].getName();
			File sampleProjectTempLocation = new File(tempDir, sampleName);

			File projectDesc = new File(sampleProjectTempLocation, ".project");
			if (!sampleName.equals(newProject.getName())) {
				if (projectDesc.exists() && projectDesc.isFile()) {
					String parameterValue = newProject.getName();
					updateWithParameterData(projectDesc, parameterValue);
				}
			}

			FileUtils.copyDirectoryContents(sampleProjectTempLocation, projectTempLocation);
			FileUtils.copyDirectoryContents(projectTempLocation, target);
			newProject.refreshLocal(IResource.DEPTH_INFINITE, null);
			newProject.close(new NullProgressMonitor());
			newProject.refreshLocal(IResource.DEPTH_INFINITE, null);
			newProject.open(new NullProgressMonitor());
			newProject.refreshLocal(IResource.DEPTH_INFINITE, null);
			sampleTempTag.clearAndEnd();
			return true;
		} catch (CoreException | IOException e) {
			try {
				newProject.delete(true, null);
			} catch (CoreException e1) {
				log.error("An Exception was thrown in deleting the new project  : " + newProject.getName() +
				          ", with the selected sample : " + projectArchiveLoc, e);
			}
			log.error("An Exception was thrown in creating the new project  : " + projectName +
			          ", with the selected sample : " + projectArchiveLoc, e);
			MultiStatus status =
			                     MessageDialogUtils.createMultiStatus(e.getLocalizedMessage(), e,
			                                                          WSO2PluginConstants.PACKAGE_ID);
			// show error dialog
			ErrorDialog.openError(this.getShell(), WSO2PluginConstants.ERROR_DIALOG_TITLE,
			                      "An Error Occurred in creating the specifid plugin. ", status);
			return false;
		}
	}

	protected void updateWithParameterData(File projectDesc, String parameterValue) throws IOException {
		String content = FileUtils.getContentAsString(projectDesc);
		content = MessageFormat.format(content, parameterValue);
		projectDesc.delete();
		FileUtils.writeContent(projectDesc, content);
	}

	public boolean canFinish() {
		return isWizardSelected;
	}

	public boolean isWizardSelected() {
		return isWizardSelected;
	}

	public void setWizardSelected(boolean isWizardSelected) {
		this.isWizardSelected = isWizardSelected;
	}

	public ISelection getSelectedPlugin() {
		return selectedPlugin;
	}

	public void setSelectedPlugin(ISelection selectedPlugin) {
		this.selectedPlugin = selectedPlugin;
	}

	protected URL getWizardManifest() {
		if (configElement != null) {
			String wizardManifestPath = configElement.getAttribute("wizardManifest");

			URL docBaseResource =
			                      FileLocator.find(Platform.getBundle(configElement.getContributor().getName()),
			                                       new Path(wizardManifestPath), null);
			try {
				return FileLocator.toFileURL(docBaseResource);
			} catch (IOException e) {
				log.error("an exception occurred in extracting the Wizard manifest from bundle", e);
				return null;
			}
		}
		return null;
	}

}
