package org.jenkinsci.plugins.BuildRotator;

import hudson.Extension;
import hudson.model.Job;
import hudson.model.Run;
import hudson.tasks.LogRotator;
import jenkins.model.BuildDiscarder;
import jenkins.model.BuildDiscarderDescriptor;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.IOException;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.logging.Logger;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.FINER;

/**
 * {@link BuildRotator} is memory footprint friendly replacement for {@link LogRotator}.
 * It doesn't calculate a real number of builds in history (this was implemented to reduce memory consumption).
 * It assumes that every build with build number smaller then
 * ("last build number" - "number of builds it should keep") could be removed.
 *
 * There is no difference how {@link BuildRotator} removes builds based on days limit.
 *
 * @author Alexander Akbashev
 */
@SuppressWarnings({"unused", "WeakerAccess"})
public class BuildRotator extends BuildDiscarder {
    private static final Logger LOGGER = Logger.getLogger(BuildRotator.class.getName());

    private enum Action {DELETE_ARTIFACT, DELETE_BUILD}

    private final int daysToKeep;
    private final int numToKeep;
    private final int artifactsDaysToKeep;
    private final int artifactsNumToKeep;
    private final boolean keepLastSuccessful;
    private final boolean keepLastStable;

    @DataBoundConstructor
    public BuildRotator(int daysToKeep, int numToKeep, int artifactsDaysToKeep, int artifactsNumToKeep,
        boolean keepLastSuccessful, boolean keepLastStable) {
        super();
        this.daysToKeep = daysToKeep;
        this.numToKeep = numToKeep;
        this.artifactsDaysToKeep = artifactsDaysToKeep;
        this.artifactsNumToKeep = artifactsNumToKeep;
        this.keepLastSuccessful = keepLastSuccessful;
        this.keepLastStable = keepLastStable;
    }

    @SuppressWarnings("unused")
    public int getArtifactsDaysToKeep() {
        return artifactsDaysToKeep;
    }

    @SuppressWarnings("unused")
    public int getArtifactsNumToKeep() {
        return artifactsNumToKeep;
    }

    @SuppressWarnings("unused")
    public int getDaysToKeep() {
        return daysToKeep;
    }

    @SuppressWarnings("unused")
    public int getNumToKeep() {
        return numToKeep;
    }

    @SuppressWarnings("unused")
    public boolean isKeepLastSuccessful() {
        return keepLastSuccessful;
    }

    @SuppressWarnings("unused")
    public boolean isKeepLastStable() {
        return keepLastStable;
    }

    @Override
    public void perform(Job<?, ?> job) throws IOException, InterruptedException {
        LOGGER.log(FINE, "Running the log rotation for {0} with numToKeep={1} " +
                "daysToKeep={2} " +
                "artifactsNumToKeep={3} " +
                "artifactsDaysToKeep={4}", new Object[]{job, numToKeep, daysToKeep, artifactsNumToKeep, artifactsDaysToKeep});

        // always keep the last successful and the last stable builds
        final Run lastSuccessfulBuild = job.getLastSuccessfulBuild();
        final Run lastStableBuild = job.getLastStableBuild();

        if (numToKeep != -1) {
            removeByNumber(job, lastSuccessfulBuild, lastStableBuild, Action.DELETE_BUILD);
        }

        if (daysToKeep != -1) {
            removeByDate(job, lastSuccessfulBuild, lastStableBuild, Action.DELETE_BUILD);
        }

        if (artifactsNumToKeep != -1) {
            removeByNumber(job, lastSuccessfulBuild, lastStableBuild, Action.DELETE_ARTIFACT);
        }

        if (artifactsDaysToKeep != -1) {
            removeByDate(job, lastSuccessfulBuild, lastStableBuild, Action.DELETE_ARTIFACT);
        }
    }

    private void removeByNumber(Job<?, ?> job, Run lastSuccessfulBuild, Run lastStableBuild, Action action) throws IOException {
        Run<?, ?> lastCompletedBuild = job.getLastCompletedBuild();
        if (lastCompletedBuild != null) {
            int lastJob = lastCompletedBuild.getNumber();
            int minNumber = lastJob - numToKeep;
            Run r = job.getFirstBuild();
            while (r != null) {
              if (r.getNumber() > minNumber) {
                break;
              }
              remove(lastSuccessfulBuild, lastStableBuild, action, r);
              r = r.getNextBuild();
            }
        } else {
            LOGGER.log(FINE, "There are no builds to remove for {0}", job);
        }
    }

    private void removeByDate(Job<?, ?> job, Run lastSuccessfulBuild, Run lastStableBuild, Action action) throws IOException {
        Calendar cal = new GregorianCalendar();
        cal.add(Calendar.DAY_OF_YEAR, -daysToKeep);
        Run r = job.getFirstBuild();
        while (r != null) {
            if (tooNew(r, cal)) {
                break;
            }
            remove(lastSuccessfulBuild, lastStableBuild, action, r);
            r = r.getNextBuild();
        }
    }

    private void remove(Run lastSuccessfulBuild, Run lastStableBuild, Action action, Run r) throws IOException {
        if (!r.isBuilding() && !shouldKeepRun(r, lastSuccessfulBuild, lastStableBuild)) {
            if (action == Action.DELETE_BUILD) {
                LOGGER.log(FINE, "{0} is to be removed", r);
                if (r.getRootDir().isDirectory()) {
                    r.delete();
                }
            } else {
                LOGGER.log(FINE, "{0} is to be purged of artifacts", r);
                r.deleteArtifacts();
            }
        }
    }

    private boolean shouldKeepRun(Run run, Run lastSuccessfulBuild, Run lastStableBuild) {
        if (run.isKeepLog()) {
            LOGGER.log(FINER, "{0} is not to be removed or purged of artifacts because it’s marked as a keeper", run);
            return true;
        }
        if (run == lastSuccessfulBuild && keepLastSuccessful) {
            LOGGER.log(FINER, "{0} is not to be removed or purged of artifacts because it’s the last successful build", run);
            return true;
        }
        if (run == lastStableBuild && keepLastStable) {
            LOGGER.log(FINER, "{0} is not to be removed or purged of artifacts because it’s the last stable build", run);
            return true;
        }
        return false;
    }

    private boolean tooNew(Run r, Calendar cal) {
        if (!r.getTimestamp().before(cal)) {
            LOGGER.log(FINER, "{0} is not to be removed or purged of artifacts because it’s still new", r);
            return true;
        } else {
            return false;
        }
    }

    @Extension
    public static final class LRDescriptor extends BuildDiscarderDescriptor {
        public String getDisplayName() {
            return "Build Rotation";
        }
    }
}

