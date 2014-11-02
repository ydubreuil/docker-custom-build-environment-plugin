package com.cloudbees.jenkins.plugins.okidocki;

import hudson.model.InvisibleAction;

/**
 * @author <a href="mailto:yoann.dubreuil@gmail.com">Yoann Dubreuil</a>
 */
public class DockerEnvironment extends InvisibleAction {
    final private String containerId;

    private boolean runInContainer = true;

    public DockerEnvironment(final String containerId) {
        this.containerId = containerId;
    }

    public String getContainerId() {
        return containerId;
    }

    public boolean isRunInContainer() {
        return runInContainer;
    }

    public void setRunInContainer(boolean runInContainer) {
        this.runInContainer = runInContainer;
    }
}
