package com.cloudbees.jenkins.plugins.okidocki;

import hudson.FilePath;
import hudson.Launcher;
import hudson.Proc;
import hudson.model.TaskListener;
import hudson.remoting.VirtualChannel;
import hudson.util.ArgumentListBuilder;
import org.apache.commons.io.FileUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public class Docker {
    
    private final Launcher launcher;
    private final TaskListener listener;

    public Docker(Launcher launcher, TaskListener listener) {
        this.launcher = launcher;
        this.listener = listener;
    }

    public boolean hasImage(String image) throws IOException, InterruptedException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int status = launcher.launch()
                .cmds("docker", "inspect", image)
                .stdout(out).stderr(err).join();
        return status == 0;
    }

    public boolean pullImage(String image) throws IOException, InterruptedException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int status = launcher.launch()
                .cmds("docker", "pull", image)
                .stdout(out).stderr(err).join();
        return status == 0;
    }


    public void buildImage(FilePath workspace, String tag) throws IOException, InterruptedException {

        int status = launcher.launch()
                .pwd(workspace.getRemote())
                .cmds("docker", "build", "-t", tag, ".")
                .stdout(listener.getLogger()).stderr(listener.getLogger()).join();
        if (status != 0) {
            throw new RuntimeException("Failed to build docker image from project Dockerfile");
        }
    }

    public void run(String image, FilePath workspace, String user, String command) throws IOException, InterruptedException {

        int status = launcher.launch()
                .cmds("docker", "run", "-t",
                        "-v", workspace.getRemote()+":/var/workspace:rw",
                        image, command
                     )
                .writeStdin().stdout(listener.getLogger()).stderr(listener.getLogger()).join();

        if (status != 0) {
            throw new RuntimeException("Failed to run docker image");
        }
    }

    public void stop(String container) throws IOException, InterruptedException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int status = launcher.launch()
                .cmds("docker", "stop", container)
                .stdout(out).stderr(err).join();
        status = launcher.launch()
                .cmds("docker", "rm", container)
                .stdout(out).stderr(err).join();
    }

    public String runDetached(String image, FilePath workspace) throws IOException, InterruptedException {
        String tmp;
        try {
            tmp = workspace.act(GetTmpdir);
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted");
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int status = launcher.launch()
                .cmds("docker", "run",
                        //"-u", "$(id -u):$(id -g)", // give same credentials as running user
                        "-td", // allocate a tty to block 'cat' and detach
                        "-v", workspace.getRemote()+":/var/workspace:rw",
                        "-v", tmp + ":" + tmp + ":rw",
                        image, "cat")
                .stdout(out).stderr(err).join();
        if (status != 0) {
            throw new RuntimeException("Failed to start docker image");
        }

        return new String(out.toByteArray(), "UTF-8").replaceAll("[\n\r]", "");
    }

    private static FilePath.FileCallable<String> GetTmpdir = new FilePath.FileCallable<String>() {
        @Override
        public String invoke(File f, VirtualChannel channel) throws IOException, InterruptedException {
            return System.getProperty("java.io.tmpdir");
        }
    };
}
