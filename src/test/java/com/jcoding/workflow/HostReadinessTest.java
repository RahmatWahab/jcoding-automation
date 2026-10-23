package com.jcoding.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HostReadinessTest {
    @Test
    void probeMountsTheSameSystemPathsAsTheRealSandboxSoMergedUsrHostsPass() {
        List<String> command = HostReadiness.privateNetworkProbeCommand(
                path -> Set.of("/usr", "/bin", "/lib", "/lib64").contains(path));
        // The dynamic loader lives under /lib64 on merged-/usr hosts; without it python3 cannot exec.
        assertThat(command).containsSequence("--ro-bind", "/lib64", "/lib64");
        assertThat(command).containsSequence("--ro-bind", "/lib", "/lib");
        assertThat(command).containsSequence("--ro-bind", "/usr", "/usr");
        assertThat(command).doesNotContain("/sbin");
        assertThat(command).contains("--unshare-all");
    }

    @Test
    void probeOnlyBindsPathsThatExist() {
        List<String> command = HostReadiness.privateNetworkProbeCommand(path -> path.equals("/usr"));
        assertThat(command).containsSequence("--ro-bind", "/usr", "/usr");
        assertThat(command).doesNotContain("/lib64", "/lib", "/bin", "/sbin");
    }
}
