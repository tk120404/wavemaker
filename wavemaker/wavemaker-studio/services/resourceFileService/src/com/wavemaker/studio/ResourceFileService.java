/*
 * Copyright (C) 2010-2011 VMware, Inc. All rights reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.wavemaker.studio;

import java.io.IOException;
import java.util.Hashtable;

import org.apache.log4j.Logger;
import org.springframework.core.io.Resource;
import org.springframework.util.FileCopyUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import com.wavemaker.common.WMRuntimeException;
import com.wavemaker.runtime.server.DownloadResponse;
import com.wavemaker.runtime.server.FileUploadResponse;
import com.wavemaker.runtime.server.ParamName;
import com.wavemaker.runtime.service.annotations.ExposeToClient;
import com.wavemaker.runtime.service.annotations.HideFromClient;
import com.wavemaker.tools.project.ProjectManager;
import com.wavemaker.tools.project.StudioFileSystem;

/**
 * This is a client-facing service class. All public methods will be exposed to the client. Their return values and
 * parameters will be passed to the client or taken from the client, respectively. This will be a singleton instance,
 * shared between all requests.
 */
@ExposeToClient
public class ResourceFileService {

    protected final Logger logger = Logger.getLogger(getClass());

    private ProjectManager projectManager;

    private StudioFileSystem fileSystem;

    // Used by spring
    @HideFromClient
    public void setProjectManager(ProjectManager manager) {
        this.projectManager = manager;
    }

    @HideFromClient
    public void setFileSystem(StudioFileSystem fileSystem) {
        this.fileSystem = fileSystem;
    }

    @HideFromClient
    protected Resource getProjectDir() {
        return this.projectManager.getCurrentProject().getWebAppRoot();
    }

    /*
     * Gets the project's resources folder; initializes it if it doesn't yet exist
     */
    @HideFromClient
    private Resource getResourcesDir() {
        Resource resources;
        try {
            resources = getProjectDir().createRelative("resources/");
        } catch (IOException e) {
            throw new WMRuntimeException(e);
        }
        if (!resources.exists()) {
            resources = this.fileSystem.createPath(getProjectDir(), "resources/");
            this.fileSystem.createPath(resources, "images/imagelists/");
            this.fileSystem.createPath(resources, "images/buttons/");
            this.fileSystem.createPath(resources, "images/logos/");
            this.fileSystem.createPath(resources, "javascript/");
            this.fileSystem.createPath(resources, "css/");
            this.fileSystem.createPath(resources, "htmlcontent/");
        }
        return resources;
    }

    protected Resource getRequestedFile(String requestedFile, boolean isFolder) throws IOException {
        return getRequestedFile(requestedFile, isFolder, false);
    }

    protected Resource getRequestedFile(String requestedFile, boolean isFolder, boolean create) throws IOException {
        Resource root;
        if (requestedFile.startsWith("/common")) {
            try {
                root = this.fileSystem.getCommonDir();
            } catch (IOException e) {
                root = this.projectManager.getCurrentProject().getProjectRoot(); // don't know what to do if exception
                                                                                 // thrown...
            }
            requestedFile = requestedFile.substring(7);
            System.out.println("requestedFile:" + requestedFile);
        } else {
            root = this.projectManager.getCurrentProject().getProjectRoot();
        }
        if (requestedFile != null && requestedFile.length() > 0) {
            if (create) {
                return this.fileSystem.createPath(root, requestedFile + (isFolder ? "/" : ""));
            } else {
                return root.createRelative(requestedFile + (isFolder ? "/" : ""));
            }
        } else {
            return root;
        }
    }

    /* Respond's to user request to download a resource file */
    public DownloadResponse downloadFile(@ParamName(name = "folder") String folderpath, @ParamName(name = "filename") String filename)
        throws IOException {
        boolean isZip = false;
        Resource parentDir = getRequestedFile(folderpath, true);
        Resource localFile = parentDir.createRelative(filename + (filename.indexOf(".") == -1 ? "/" : ""));
        if (StringUtils.getFilenameExtension(filename) == null) {
            localFile = com.wavemaker.tools.project.ResourceManager.createZipFile(this.fileSystem, localFile, this.projectManager.getTmpDir());
            if (localFile == null) {
                return null;
            }
            isZip = true;
            filename = localFile.getFilename();
        }

        return com.wavemaker.tools.project.ResourceManager.downloadFile(localFile, filename, isZip);

    }

    /*
     * Respond's to user's request to rename/move a file. Will append a number to the name if there is already a file
     * with the requested name
     */
    public String renameFile(@ParamName(name = "from") String from, @ParamName(name = "to") String to,
        @ParamName(name = "overwrite") boolean overwrite) {

        try {
            Resource dest = getRequestedFile(to, to.indexOf(".") == -1);
            if (!overwrite) {
                int lastIndexOfPeriod = to.lastIndexOf(".");
                String to1 = lastIndexOfPeriod != -1 ? to.substring(0, to.lastIndexOf(".")) : to;
                String to_ext = lastIndexOfPeriod != -1 ? to.substring(to.lastIndexOf(".") + 1) : "";
                for (int i = 0; i < 1000 && dest.exists(); i++) {
                    dest = getRequestedFile(to1 + i + (lastIndexOfPeriod != -1 ? "." : "") + to_ext, to.indexOf(".") == -1);
                }
            }
            Resource f = getRequestedFile(from, from.indexOf(".") == -1);
            System.out.println("RENAME " + f.getDescription() + " TO " + dest.getDescription());
            this.fileSystem.rename(f, dest);
            return dest.getFilename();
        } catch (Exception e) {
            throw new WMRuntimeException(e);
        }
    }

    /*
     * Moves a file that was uploaded to the tmp folder to the requested destination. All uploads go to tmp folder
     * 
     * public String moveNewFile(@ParamName(name = "from") String from,
     * 
     * @ParamName(name = "to") String to,
     * 
     * @ParamName(name = "overwrite") boolean overwrite) { Resource resourceDir = this.getResourcesDir();
     * 
     * try { Resource dest = resourceDir.createRelative(to);
     * 
     * if (!overwrite) { String to1 = (to.lastIndexOf(".") != -1) ? to.substring(0, to.lastIndexOf(".")) : to; String
     * to_ext = (to.lastIndexOf(".") != -1) ? to.substring(to .lastIndexOf(".") + 1) : ""; for (int i = 0; i < 1000 &&
     * dest.exists(); i++) { dest = resourceDir.createRelative(to1 + i + "." + to_ext); } }
     * 
     * Resource f = projectManager.getTmpDir().createRelative(from); studioConfiguration.rename(f, dest); return
     * dest.getFilename(); } catch (Exception e) { throw new WMRuntimeException(e); } }
     */
    /*
     * Create a folder; name should have the full relative path within the resources folder
     */
    public boolean createFolder(@ParamName(name = "name") String name) {
        try {
            Resource newFolder = getRequestedFile(name, true, true);
            return newFolder.exists();
        } catch (Exception e) {
            throw new WMRuntimeException(e);
        }
    }

    /*
     * Delete the file; name should be the full relative path within resources folder
     */
    public boolean deleteFile(@ParamName(name = "name") String name) {
        try {
            Resource f = getRequestedFile(name, name.indexOf(".") == -1);
            this.fileSystem.deleteFile(f);
            return !f.exists();
        } catch (Exception e) {
            throw new WMRuntimeException(e);
        }
    }

    /*
     * Send the client a datastruct listing all contents of the resources folder. WARNING: At some point we may want to
     * support larger projects by NOT sending it all at once
     */
    public Hashtable<String, Object> getResourceFolder() throws WMRuntimeException {
        Resource resourceDir = this.getResourcesDir();
        Hashtable<String, Object> P = new Hashtable<String, Object>();
        try {
            P.put("files", com.wavemaker.tools.project.ResourceManager.getListing(this.fileSystem, resourceDir));
        } catch (Exception e) {
            throw new WMRuntimeException(e);
        }
        P.put("file", resourceDir.getFilename());
        P.put("type", "folder");
        return P;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    public Hashtable getFolder(String folderName) {
        try {
            Resource folder = getRequestedFile(folderName, true);

            Hashtable P = new Hashtable();
            P.put("files", com.wavemaker.tools.project.ResourceManager.getListing(this.fileSystem, folder));

            P.put("file", folder.getFilename());
            P.put("type", "folder");
            return P;
        } catch (Exception e) {
            throw new WMRuntimeException(e);
        }
    }

    public String readFile(String filePath) throws IOException {
        Resource f = getRequestedFile(filePath, false);
        return this.projectManager.getCurrentProject().readFile(f);
    }

    public void writeFile(String filePath, String filetext) throws IOException {
        Resource f = getRequestedFile(filePath, false);
        this.projectManager.getCurrentProject().writeFile(f, filetext);
    }

    public FileUploadResponse uploadFile(@ParamName(name = "file") MultipartFile file, String path) throws IOException {
        FileUploadResponse ret = new FileUploadResponse();
        try {
            Resource dir = getRequestedFile(path, true);
            Resource outputFile = dir.createRelative(file.getOriginalFilename().replaceAll("[^a-zA-Z0-9.\\-_ ]", ""));
            FileCopyUtils.copy(file.getInputStream(), this.fileSystem.getOutputStream(outputFile));
            ret.setPath(outputFile.getDescription());
            ret.setError("");
            ret.setWidth("");
            ret.setHeight("");

        } catch (Exception e) {
            ret.setError(e.getMessage());
        }
        return ret;
    }

    /**
     * Unzips a zip file in the tmp folder and moves it to the specified location. NOTE: Will not overwrite an existing
     * folder at that location; instead will rename to avoid collision NOTE:
     * 
     * @see ProjectManager#openProject(String)
     * @return An OpenProjectReturn object containing the current web path, as well as any upgrades that were performed.
     */
    public boolean unzipAndMoveNewFile(@ParamName(name = "file") String path) {
        try {
            Resource zipfile = getRequestedFile(path, false);
            Resource zipfolder = com.wavemaker.tools.project.ResourceManager.unzipFile(this.fileSystem, zipfile);
            return zipfolder.exists() && StringUtils.getFilenameExtension(zipfolder.getFilename()) == null;
        } catch (IOException e) {
            throw new WMRuntimeException(e);
        }
    }
}