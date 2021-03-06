/*
 *  Copyright (C) 2007-2013 VMware, Inc. All rights reserved.
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

package com.wavemaker.tools.ant;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.SortedSet;
import java.util.TreeSet;

import javax.xml.bind.JAXBException;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.types.Resource;
import org.apache.tools.ant.types.ResourceCollection;
import org.apache.tools.ant.types.resources.Resources;

import com.wavemaker.common.util.Tuple;
import com.wavemaker.tools.io.File;
import com.wavemaker.tools.io.Folder;
import com.wavemaker.tools.io.local.LocalFolder;
import com.wavemaker.tools.project.Project;
import com.wavemaker.tools.project.ProjectConstants;
import com.wavemaker.tools.service.ConfigurationCompiler;
import com.wavemaker.tools.service.DesignServiceManager;
import com.wavemaker.tools.service.FileService;
import com.wavemaker.tools.service.definitions.Service;
import com.wavemaker.tools.service.definitions.ServiceComparator;
import com.wavemaker.tools.util.DesignTimeUtils;

/**
 * Generate runtime configuration (spring and SMD; anything generated by
 * DesignServiceManager.generateRuntimeConfiguration()). The destWebAppRoot takes precedence over project, but project
 * should still be set to read project-specific configuration.
 * 
 * @author Matt Small
 * @author Jeremy Grelle
 */
public class ConfigurationCompilerTask extends CompilerTask {

    private java.io.File destWebAppRoot;

    private Folder destServicesDir;

    private final Resources union = new Resources();

    private final List<File> files = new ArrayList<File>();

    private boolean useWMResource = false;

    public ConfigurationCompilerTask() {
        super(true);
    }

    public LocalFolder getDestWebAppRoot() {
        return new LocalFolder(this.destWebAppRoot);
    }

    public void setDestWebAppRoot(java.io.File destWebAppRoot) {
        this.destWebAppRoot = destWebAppRoot;
    }

    public Folder getDestServicesDir() {
        return this.destServicesDir;
    }

    /**
     * Set a destination directory for the SMDs (runtime services).
     */
    public void setDestServicesDir(Folder destServicesDir) {
        this.destServicesDir = destServicesDir;
    }

    @Override
    public void doExecute() throws BuildException {

        Folder destination;
        FileService fileService = null;
        if (getDestWebAppRoot() != null) {
            destination = getDestWebAppRoot();
        } else if (getAGProject() != null) {
            Project p = getAGProject();
            destination = p.getWebAppRootFolder();
            fileService = p;
        } else {
            throw new BuildException("one of destWebAppRoot or projectRoot must be set");
        }

        if (fileService == null) {
            System.out.println("using " + destination + " as a default project directory; please set projectRoot");
            fileService = new Project(destination, "project");
        }

        boolean doXmlBuild = false;
        SortedSet<Service> doBuildServices = new TreeSet<Service>(new ServiceComparator());
        SortedSet<Service> allServices = new TreeSet<Service>(new ServiceComparator());
        Map<Service, Long> serviceToMtime = new HashMap<Service, Long>();

        List<Tuple.Two<InputStream, Long>> couples = new ArrayList<Tuple.Two<InputStream, Long>>();
        if (this.useWMResource) {
            for (File file : this.files) {
                couples.add(new Tuple.Two<InputStream, Long>(file.getContent().asInputStream(), file.getLastModified()));
            }
        } else {
            Iterator<?> it = getUnion().iterator();
            while (it.hasNext()) {
                try {
                    Resource resource = (Resource) it.next();
                    couples.add(new Tuple.Two<InputStream, Long>(resource.getInputStream(), resource.getLastModified()));
                } catch (IOException e) {
                    throw new BuildException(e);
                }
            }
        }

        for (Tuple.Two<InputStream, Long> couple : couples) {
            InputStream is = couple.v1;
            long lastModified = couple.v2;

            try {
                Service service = DesignServiceManager.loadServiceDefinition(is, true);
                is.close();

                if (getVerbose()) {
                    System.out.println("checking service " + service.getId());
                }

                serviceToMtime.put(service, lastModified);

                allServices.add(service);
            } catch (JAXBException e) {
                throw new BuildException(e);
            } catch (IOException ex) {
            }
        }

        Folder webInf = destination.getFolder(ProjectConstants.WEB_INF);
        File servicesXml = webInf.getFile(ConfigurationCompiler.RUNTIME_SERVICES);
        File managersXml = webInf.getFile(ConfigurationCompiler.RUNTIME_MANAGERS);
        File typesJs = destination.getFile(ConfigurationCompiler.TYPE_RUNTIME_FILE);

        Folder servicesDir;
        if (getDestServicesDir() != null) {
            servicesDir = getDestServicesDir();
        } else {
            servicesDir = destination.getFolder(ConfigurationCompiler.RUNTIME_SERVICES_DIR);
        }

        // check files for a build
        if (!servicesXml.exists() || !managersXml.exists() || !typesJs.exists()) {
            doXmlBuild = true;
        }

        try {

            // check SMDs for a build
            for (Entry<Service, Long> entry : serviceToMtime.entrySet()) {

                Service service = entry.getKey();
                long mtime = entry.getValue();
                File smdFile = ConfigurationCompiler.getSmdFile(servicesDir, service.getId());

                if (!smdFile.exists() || smdFile.getLastModified() < mtime) {
                    doBuildServices.add(service);
                    doXmlBuild = true;
                }
            }

            if (doXmlBuild) {
                ConfigurationCompiler.generateServices(servicesXml, allServices);

                ConfigurationCompiler.generateManagers(managersXml, allServices);

                System.out.println("Regenerated spring configuration for " + allServices.size() + " services");
            }

            if (0 < doBuildServices.size()) {
                ConfigurationCompiler.generateSMDs(servicesDir, doBuildServices);

                System.out.println("Configured " + doBuildServices.size() + " services");
            }

            // if we're doing any sort of build, generate types
            if (doXmlBuild || 0 < doBuildServices.size()) {
                DesignServiceManager dsm = DesignTimeUtils.getDSMForProjectRoot(fileService.getFileServiceRoot());
                ConfigurationCompiler.generateTypes(typesJs, allServices, dsm.getPrimitiveDataObjects());

                System.out.println("Regenerated types & services information");
            }
        } catch (JAXBException ex) {
            throw new BuildException(ex);
        } catch (IOException ex) {
            throw new BuildException(ex);
        } catch (NoSuchMethodException ex) {
            throw new BuildException(ex);
        }
    }

    public Resources getUnion() {
        return this.union;
    }

    public void add(ResourceCollection rc) {
        this.union.add(rc);
    }

    public List<com.wavemaker.tools.io.File> getFiles() {
        return this.files;
    }

    public void addWmResource(File rc) {
        this.files.add(rc);
        this.useWMResource = true;
    }
}
