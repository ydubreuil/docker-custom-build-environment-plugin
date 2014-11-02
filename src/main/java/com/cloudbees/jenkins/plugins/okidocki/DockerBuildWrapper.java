package com.cloudbees.jenkins.plugins.okidocki;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Proc;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Descriptor;
import hudson.model.Run;
import hudson.remoting.VirtualChannel;
import hudson.tasks.BuildWrapper;
import hudson.tasks.BuildWrapperDescriptor;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Decorate Launcher so that every command executed by a build step is actually ran inside docker container.
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 * @author <a href="mailto:yoann.dubreuil@gmail.com">Yoann Dubreuil</a>
 */
public class DockerBuildWrapper extends BuildWrapper {

    public DockerImageSelector selector;

    @DataBoundConstructor
    public DockerBuildWrapper(DockerImageSelector selector) {
        this.selector = selector;
    }

    @Override
    public Environment setUp(AbstractBuild build, Launcher launcher, BuildListener listener)
            throws IOException, InterruptedException {

        final Docker docker = new Docker(launcher, listener);
        listener.getLogger().println("[docker] prepare Docker image to host the build environment");
        String image;
        try {
            image = selector.prepareDockerImage(docker, build, listener);
            build.addAction(new DockerBadge(image));
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted");
        }

        listener.getLogger().println("[docker] start Docker container");
        String containerId = docker.runDetached(image, build.getWorkspace());
        final DockerEnvironment dockerEnvironment = new DockerEnvironment(containerId);
        build.addAction(dockerEnvironment);

        return new Environment() {
            @Override
            public boolean tearDown(AbstractBuild build, BuildListener listener) throws IOException, InterruptedException {
                listener.getLogger().println("[docker] remove Docker container");

                dockerEnvironment.setRunInContainer(false);
                docker.stop(dockerEnvironment.getContainerId());

                return true;
            }
        };
    }

    @Override
    public Launcher decorateLauncher(final AbstractBuild build, final Launcher launcher, final BuildListener listener) throws IOException, InterruptedException, Run.RunnerAbortedException {
        return new Launcher.DecoratedLauncher(launcher) {
            @Override
            public Proc launch(ProcStarter starter) throws IOException {
                DockerEnvironment dockerEnvironment = build.getAction(DockerEnvironment.class);
                if (dockerEnvironment == null || !dockerEnvironment.isRunInContainer()) {
                    return super.launch(starter);
                } else {
                    List<String> cmds = new ArrayList<String>();
                    //cmds.add("gdbserver");
                    //cmds.add("localhost:8888");
                    cmds.add("docker");
                    cmds.add("exec");
                    cmds.add("-t");
                    cmds.add(dockerEnvironment.getContainerId());
                    cmds.addAll(starter.cmds());
                    starter.cmds(cmds);
                    // FIXME: copy origin masks array
                    starter.masks(new boolean[cmds.size()]);
                    return super.launch(starter);
                }
            }
        };
    }

    @Extension
    public static class DescriptorImpl extends BuildWrapperDescriptor {

        @Override
        public String getDisplayName() {
            return "Build inside a Docker container";
        }

        public Collection<Descriptor<DockerImageSelector>> selectors() {
            return Jenkins.getInstance().getDescriptorList(DockerImageSelector.class);
        }

        @Override
        public boolean isApplicable(AbstractProject<?, ?> item) {
            return true;
        }
    }
}
