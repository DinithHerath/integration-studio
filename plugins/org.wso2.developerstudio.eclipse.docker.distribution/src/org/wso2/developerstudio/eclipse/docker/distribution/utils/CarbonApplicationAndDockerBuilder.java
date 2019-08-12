/*
 * Copyright (c) 2019, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
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
package org.wso2.developerstudio.eclipse.docker.distribution.utils;

import com.spotify.docker.client.DefaultDockerClient;
import com.spotify.docker.client.DockerClient;
import com.spotify.docker.client.ProgressHandler;
import com.spotify.docker.client.exceptions.DockerException;
import com.spotify.docker.client.messages.ProgressMessage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.commons.io.FileUtils;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PlatformUI;
import org.wso2.developerstudio.eclipse.docker.distribution.Activator;
import org.wso2.developerstudio.eclipse.logging.core.IDeveloperStudioLog;
import org.wso2.developerstudio.eclipse.logging.core.Logger;
import org.wso2.developerstudio.eclipse.maven.util.MavenUtils;
import org.wso2.developerstudio.eclipse.platform.core.project.export.util.ExportUtil;

/**
 * This class is used to export carbon applications to a given location and
 * to create docker images.
 */
public class CarbonApplicationAndDockerBuilder extends Job {

    private List<String> dependencyProjectNames;
    private String carbonAppsFolderLocation;
    private String dockerFileLocation;
    private String repository;
    private String tag;
    private String imageId;

    private final String POM_XML = "pom.xml";
    private static final String CAPP_NATURE = "org.wso2.developerstudio.eclipse.distribution.project.nature";
    private Map<String, IProject> carbonApplicationList = new HashMap<String, IProject>();
    private static IDeveloperStudioLog log = Logger.getLog(Activator.PLUGIN_ID);

    public String getImageId() {
        return imageId;
    }

    public CarbonApplicationAndDockerBuilder(List<String> dependencyProjectNames, String carbonAppsFolderLocation,
            String dockerFileLocation, String repository, String tag) {
        super("Generating Docker Image...");
        this.dependencyProjectNames = dependencyProjectNames;
        this.carbonAppsFolderLocation = carbonAppsFolderLocation;
        this.dockerFileLocation = dockerFileLocation;
        this.repository = repository;
        this.tag = tag;
    }

    public Map<String, IProject> getCarbonApplicationList() {
        return carbonApplicationList;
    }

    /**
     * Generating a docker image using Spotify docker plugin.
     */
    private void generateDockerImage() {

        // Detect the operating system
        String operatingSystem = System.getProperty(DockerProjectConstants.OS_NAME).toLowerCase(Locale.getDefault());

        // Set default docker host based on the Operating System
        String dockerHost = "";
        if (operatingSystem.contains(DockerProjectConstants.WINDOWS)) {
            dockerHost = DockerProjectConstants.WINDOWS_DEFAULT_DOCKER_HOST;
        } else {
            dockerHost = DockerProjectConstants.UNIX_DEFAULT_DOCKER_HOST;
        }

        DockerClient docker = DefaultDockerClient.builder().uri(dockerHost).build();

        // Test connection to Docker server
        try {
            docker.ping();
        } catch (DockerException | InterruptedException e1) {
            log.error(DockerProjectConstants.DOCKER_CONNECTION_FAILED_MESSAGE, e1);
        }

        // Atomic reference to store the generated docker image ID
        final AtomicReference<String> imageIdFromMessage = new AtomicReference<>();
        try {
            // build the image
            imageId = docker.build(Paths.get(dockerFileLocation), repository + ":" + tag, new ProgressHandler() {
                @Override
                public void progress(ProgressMessage message) throws DockerException {
                    final String imageId = message.buildImageId();
                    // Image creation successful
                    if (imageId != null) {
                        log.info(DockerProjectConstants.DOCKER_IMAGE_GEN_SUCCESS_MESSAGE + imageId);
                        imageIdFromMessage.set(imageId);
                    }
                }
            }, DockerClient.BuildParam.noCache(), DockerClient.BuildParam.forceRm());

        } catch (DockerException | InterruptedException | IOException e2) {
            log.error(DockerProjectConstants.DOCKER_IMAGE_GEN_FAILED_MESSAGE, e2);
        }
    }

    /**
     * Export the carbon application to a given folder location.
     * 
     * @param project Carbon application project.
     * @param location Destination folder.
     * @throws IOException Error occurred while copying file.
     * @throws XmlPullParserException Error occurred while parsing the pom file.
     */
    private void exportCarToDockerFolder(IProject project, String location) throws IOException, XmlPullParserException {
        IFile pomFileRes = project.getFile(POM_XML);
        File pomFile = pomFileRes.getLocation().toFile();
        MavenProject parentPrj = MavenUtils.getMavenProject(pomFile);
        String finalFileName = String.format("%s_%s.car", parentPrj.getModel().getArtifactId(),
                parentPrj.getModel().getVersion());
        try {
            IResource CarbonArchive = ExportUtil.buildCAppProject(pomFileRes.getProject());
            File destFileName = new File(location, finalFileName);
            if (destFileName.exists()) {
                org.apache.commons.io.FileUtils.deleteQuietly(destFileName);
            }
            FileUtils.copyFile(CarbonArchive.getLocation().toFile(), destFileName);
        } catch (Exception e) {
            log.error("An error occurred while creating the carbon archive file", e);
        }

    }

    @Override
    protected IStatus run(IProgressMonitor monitor) {
        monitor.beginTask("Start generating the docker image", 100);
        monitor.subTask("Fetching all Carbon Application projects");
        // Fetch all carbon applications in the workspace.
        IWorkspaceRoot workspaceRoot = ResourcesPlugin.getWorkspace().getRoot();
        IProject[] projects = workspaceRoot.getProjects();
        try {
            for (IProject project : projects) {
                IFile pomFileRes = project.getFile(POM_XML);
                if (project.isOpen() && pomFileRes.exists() && project.hasNature(CAPP_NATURE)) {
                    String cappName = pomFileRes.getProject().getName();
                    carbonApplicationList.put(cappName, project);
                }
            }
        } catch (CoreException e) {
            log.error("Error occurred while checking the nature of the project", e);
            monitor.beginTask(e.getMessage(), 100);
            monitor.worked(0);
            monitor.setCanceled(true);
        } finally {
            monitor.worked(10);
        }

        // Filter dependency carbon applications.
        monitor.subTask("Filtering dependency Carbon Application projects");
        List<IProject> dependentProjects = new ArrayList<>();
        for (Map.Entry<String, IProject> entry : carbonApplicationList.entrySet()) {
            if (dependencyProjectNames.contains(entry.getKey())) {
                dependentProjects.add(entry.getValue());
            }
        }
        monitor.worked(5);

        // Export all the carbon applications to the target folder
        monitor.subTask("Exporting dependent CarbonApps to the target folder");
        for (IProject project : dependentProjects) {
            try {
                exportCarToDockerFolder(project, carbonAppsFolderLocation);
            } catch (IOException | XmlPullParserException e) {
                log.error("Error occurred while exporting the CarbonApp to the target folder", e);
                monitor.beginTask(e.getMessage(), 100);
                monitor.worked(0);
                monitor.setCanceled(true);
            }
        }
        monitor.worked(50);

        // Generating the docker image
        monitor.subTask("Exporting docker image");
        generateDockerImage();
        monitor.worked(100);
        monitor.done();
        showMessageBox(DockerProjectConstants.MESSAGE_BOX_TITLE,
                DockerProjectConstants.DOCKER_IMAGE_GEN_SUCCESS_MESSAGE + imageId, SWT.ICON_ERROR);
        return Status.OK_STATUS;
    }

    private void showMessageBox(final String title, final String message, final int style) {

        Display.getDefault().syncExec(new Runnable() {
            public void run() {
                Display display = PlatformUI.getWorkbench().getDisplay();
                Shell shell = display.getActiveShell();

                MessageBox exportMsg = new MessageBox(shell, style);
                exportMsg.setText(title);
                exportMsg.setMessage(message);

                exportMsg.open();
            }
        });
    }
}
