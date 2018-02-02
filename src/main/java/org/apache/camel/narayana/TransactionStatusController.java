package org.apache.camel.narayana;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.arjuna.ats.arjuna.AtomicAction;
import com.arjuna.ats.arjuna.common.Uid;
import com.arjuna.ats.arjuna.objectstore.StoreManager;
import com.arjuna.ats.arjuna.recovery.RecoveryManager;
import com.arjuna.ats.arjuna.state.InputObjectState;
import com.arjuna.ats.internal.arjuna.common.UidHelper;
import org.apache.camel.CamelContext;
import org.apache.camel.ServiceStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.jta.narayana.NarayanaProperties;

public class TransactionStatusController {

    private static final Logger LOG = LoggerFactory.getLogger(TransactionStatusController.class);

    private NarayanaProperties narayanaProperties;

    private CamelContext context;

    public TransactionStatusController(NarayanaProperties narayanaProperties, CamelContext context) {
        this.narayanaProperties = narayanaProperties;
        this.context = context;
    }

    public void start() throws IOException {
        writeStatus("RUNNING");
    }

    public void stop() {
        try {
            LOG.info("Waiting for Camel context to fully stop...");
            ServiceStatus camelStatus = context.getStatus();
            while(!camelStatus.isStopped()) {
                Thread.sleep(1000);
                camelStatus = context.getStatus();
            }
            LOG.info("Camel context stopped");

            LOG.info("Performing transaction recovery scan...");
            RecoveryManager.manager().scan();
            LOG.info("Performing second run of transaction recovery scan...");
            RecoveryManager.manager().scan();

            List<Uid> pendingUids = getPendingUids();
            if (pendingUids.isEmpty()) {
                LOG.info("There are no pending transactions left");
                writeStatus("TERMINATED");
            } else {
                LOG.warn("There are pending transactions: {}", pendingUids);
            }

        } catch (Exception ex) {
            LOG.error("Error while cleaning transaction subsystem", ex);
        }
    }

    private List<Uid> getPendingUids() throws Exception {
        List<Uid> uidList = new ArrayList<>();
        InputObjectState uids = new InputObjectState();
        if (!StoreManager.getRecoveryStore().allObjUids(new AtomicAction().type(), uids)) {
            throw new RuntimeException("Cannot obtain pending Uids");
        }

        if (uids.notempty()) {
            Uid u;
            do {
                u = UidHelper.unpackFrom(uids);

                if (Uid.nullUid().notEquals(u))
                {
                    uidList.add(u);
                }
            } while (Uid.nullUid().notEquals(u));
        }
        return uidList;
    }

    private void writeStatus(String status) throws IOException {
        LOG.info("Writing transaction status {}", status);
        File logDir = new File(narayanaProperties.getLogDir());
        if (!logDir.exists() && !logDir.mkdirs()) {
            throw new IllegalStateException("Cannot create log dir at " + logDir.getAbsolutePath());
        }
        File statusFile = new File(logDir.getParentFile(), "status");
        if (!statusFile.exists() && !statusFile.createNewFile()) {
            throw new IllegalStateException("Cannot create status file");
        }

        try (FileWriter out = new FileWriter(statusFile)) {
            out.write(status);
        }
    }

}
