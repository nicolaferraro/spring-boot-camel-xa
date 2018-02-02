package org.apache.camel.narayana;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.extensions.StatefulSet;
import io.fabric8.kubernetes.api.model.extensions.StatefulSetBuilder;
import io.fabric8.openshift.client.DefaultOpenShiftClient;
import io.fabric8.openshift.client.OpenShiftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.jta.narayana.NarayanaProperties;
import org.springframework.scheduling.annotation.Scheduled;

public class TransactionalClusterController {

    private static final Logger LOG = LoggerFactory.getLogger(TransactionalClusterController.class);

    private NarayanaProperties narayanaProperties;

    private String controllerName;

    private String podName;

    public TransactionalClusterController(NarayanaProperties narayanaProperties, String controllerName, String podName) {
        this.narayanaProperties = narayanaProperties;
        this.controllerName = controllerName;
        this.podName = podName;
    }

    @Scheduled(fixedDelayString = "${transactional.cluster.controller.delay}")
    public void periodicCheck() throws Exception {
        if (getProgressiveNumber(podName).equals(OptionalInt.of(0))) {
            // Run this on the first pod only

            try (OpenShiftClient client = new DefaultOpenShiftClient()) {

                Set<String> pendingPods = collectPodsWithPendingTransactions();
                LOG.debug("Found {} pods with pending transactions: {}", pendingPods.size(), pendingPods);
                int minReplicas = 0;
                for (String podName : pendingPods) {
                    LOG.debug("Retrieving pod {} from Openshift", podName);
                    Pod pod = client.pods().withName(podName).get();
                    if (pod == null) {
                        // pod has completely been removed from the cluster
                        LOG.debug("Pod {} not found in Openshift", podName);
                        OptionalInt prg = getProgressiveNumber(podName);
                        if (!prg.isPresent()) {
                            LOG.warn("Transaction store contains pods not belonging to the cluster StatefulSet: {}", podName);
                        } else {
                            minReplicas = Math.max(minReplicas, prg.getAsInt() + 1);
                        }
                    } else {
                        LOG.debug("Pod {} is running on Openshift", podName);
                    }
                }

                LOG.debug("StatefulSet requires a minimum of {} replicas", minReplicas);

                if (minReplicas > 1) {
                    // One pod is running, this one
                    StatefulSet statefulSet = client.apps().statefulSets().withName(controllerName).get();
                    if (statefulSet == null) {
                        LOG.warn("Cannot find StatefulSet named {} in namespace", controllerName);
                    } else {
                        int replicas = statefulSet.getSpec().getReplicas();
                        if (replicas > 0 && replicas < minReplicas) {
                            LOG.warn("Pod {}-{} has pending transactions and must be restored", controllerName, minReplicas - 1);

                            LOG.debug("Scaling the cluster back to {} replicas", minReplicas);
                            client.apps().statefulSets().withName("spring-boot-camel-xa").scale(minReplicas);
                            LOG.info("Statefulset {} successfully scaled to {} replicas", controllerName, minReplicas);
                        } else if (replicas == 0) {
                            LOG.debug("StatefulSet {} is going to be shut down to {} replicas", controllerName, replicas);
                        } else {
                            LOG.debug("StatefulSet {} has a sufficient number of replicas: {} >= {}", controllerName, replicas, minReplicas);
                        }
                    }
                }
            }


        }
    }


    private Set<String> collectPodsWithPendingTransactions() throws IOException {
        Set<String> pods = new TreeSet<>();

        File baseDir = new File(narayanaProperties.getLogDir()).getParentFile().getParentFile();
        File[] logs = baseDir.listFiles((f, name) -> name.startsWith(controllerName) && new File(f, name).isDirectory());
        if (logs == null) {
            logs = new File[0];
        }

        for (File log : logs) {
            File statusFile = new File(log, "status");
            if (!statusFile.exists()) {
                LOG.warn("Status file does not exist in transaction log directory " + log.getAbsolutePath());
                continue;
            }

            String status;
            try (BufferedReader reader = new BufferedReader(new FileReader(statusFile))) {
                status = reader.readLine();
            }
            if (status == null || !status.startsWith("TERMINATED")) {
                pods.add(log.getName());
            }
        }
        return pods;
    }

    private OptionalInt getProgressiveNumber(String podName) {
        try {
            return OptionalInt.of(Integer.parseInt(podName.substring(controllerName.length() + 1), 10));
        } catch (Exception e) {
            return OptionalInt.empty();
        }
    }

}
