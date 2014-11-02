package com.cloudbees.jenkins.plugins.okidocki;

import hudson.EnvVars;
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
import hudson.util.ArgumentListBuilder;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Decorate Launcher so that every command executed by a build step is actually ran inside docker container.
 * TODO run docker container during setup, then use docker-enter to attach command to existing container
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public class DockerBuildWrapper extends BuildWrapper {

    public DockerImageSelector selector;

    @DataBoundConstructor
    public DockerBuildWrapper(DockerImageSelector selector) {
        this.selector = selector;
    }

    @Override
    public Environment setUp(AbstractBuild build, final Launcher launcher, BuildListener listener)
            throws IOException, InterruptedException {

        return new Environment() {
            @Override
            public boolean tearDown(AbstractBuild build, BuildListener listener) throws IOException, InterruptedException {
                BuiltInContainer action = build.getAction(BuiltInContainer.class);
                if (action.container != null) {
                    action.enable = false;
                    listener.getLogger().println("Killing build container");
                    launcher.launch().cmds("docker", "kill", action.container).join();
                    launcher.launch().cmds("docker", "rm", action.container).join();
                }
                return true;
            }
        };
    }


    @Override
    public Launcher decorateLauncher(final AbstractBuild build, final Launcher launcher, final BuildListener listener) throws IOException, InterruptedException, Run.RunnerAbortedException {
        final BuiltInContainer runInContainer = new BuiltInContainer();
        build.addAction(runInContainer);

        return new Launcher.DecoratedLauncher(launcher) {
            @Override
            public Proc launch(ProcStarter starter) throws IOException {

                if (!runInContainer.enabled()) return super.launch(starter);

                // TODO only run the container first time, then ns-enter for next commands to execute.

                Docker docker = new Docker(launcher, listener);
                if (runInContainer.image == null) {
                    listener.getLogger().println("Prepare Docker image to host the build environment");
                    try {
                        runInContainer.image = selector.prepareDockerImage(docker, build, listener);
                    } catch (InterruptedException e) {
                        throw new RuntimeException("Interrupted");
                    }
                }

                if (runInContainer.container == null) {
                    try {
                        String uid = docker.runId("-u");
                        String gid = docker.runId("-g");

                        runInContainer.container = docker.runDetached(
                                runInContainer.image, build.getWorkspace(), build.getEnvironment(listener), uid, gid);
                    } catch (InterruptedException e) {
                        throw new RuntimeException("Interrupted");
                    }
                }

                ArgumentListBuilder cmdBuilder = new ArgumentListBuilder();
                cmdBuilder.addMasked("docker");
                cmdBuilder.addMasked("exec");
                cmdBuilder.addMasked("-t");
                cmdBuilder.addMasked(runInContainer.container);
                List<String> originalCmds = starter.cmds();
                boolean[] originalMask = starter.masks();
                for (int i = 0; i < originalCmds.size(); i++) {
                    boolean masked = originalMask == null ? false : i < originalMask.length ? originalMask[i] : false;
                    cmdBuilder.add(originalCmds.get(i), masked);
                }

                starter.cmds(cmdBuilder);
                return super.launch(starter);
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
