package dk.statsbiblioteket.medieplatform.autonomous.iterator.bitrepository;

import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import javax.jms.JMSException;

import dk.statsbiblioteket.medieplatform.autonomous.ResultCollector;
import org.bitrepository.client.eventhandler.EventHandler;
import org.bitrepository.client.eventhandler.OperationEvent;
import org.bitrepository.modify.putfile.PutFileClient;
import org.bitrepository.protocol.messagebus.MessageBus;
import org.bitrepository.protocol.messagebus.MessageBusManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Class handling ingest of a set of files in a tree iterator structure.
 */
public class TreeIngester {
    private final Logger log = LoggerFactory.getLogger(getClass());
    private static final long DEFAULT_FILE_SIZE = 0;

    private final ResultCollector resultCollector;
    private final IngestableFileLocator fileLocator;
    private final String collectionID;
    private final OperationEventHandler handler;
    private final ParallelOperationLimiter parallelOperationLimiter;
    private final PutFileClient putFileClient;

    /**
     *
     * @param collectionID The collectionID of the collection to store the ingested files in.
     * @param timoutForLastOperation How many milli seconfs should the ingester wait for the last operation to finish
     *                               before force quiting
     * @param fileLocator Used for finding the relevant files.
     * @param putFileClient For handling the actual ingests.
     * @param resultCollector Failures are logged here.
     * @param maxNumberOfParallelPuts The number of puts to to perform in parallel.
     */
    public TreeIngester(
            String collectionID,
            long timoutForLastOperation,
            IngestableFileLocator fileLocator,
            PutFileClient putFileClient,
            ResultCollector resultCollector,
            int maxNumberOfParallelPuts) {
        this.collectionID = collectionID;
        this.resultCollector = resultCollector;
        this.fileLocator = fileLocator;
        parallelOperationLimiter = new ParallelOperationLimiter(
                maxNumberOfParallelPuts, (int)timoutForLastOperation/1000);
        handler = new OperationEventHandler(parallelOperationLimiter);
        this.putFileClient = putFileClient;
    }

    public void performIngest() {
        IngestableFile file = null;
        do {
            try {
                file = fileLocator.nextFile();
                try {
                    if (file != null) {
                        putFile(file);
                    }
                } catch (Exception e) {
                    log.error("Failed to ingest file.", e);
                }
            } catch (Exception e) {
                log.error("Failed to find file to ingest.", e);
            }
        }  while (file != null);

        parallelOperationLimiter.waitForFinish();
    }

    /**
     * Calls the concrete putFileClient blocking if the maxNumberOfParallelPut are exceeded.
     */
    private void putFile(IngestableFile ingestableFile) {
        parallelOperationLimiter.addJob(ingestableFile.getFileID());
        putFileClient.putFile(collectionID,
                ingestableFile.getUrl(), ingestableFile.getFileID(), DEFAULT_FILE_SIZE,
                ingestableFile.getChecksum(), null, handler, null);
    }

    /**
     * Method to shutdown the client properly.
     */
    public void shutdown() {
        try {
            MessageBus messageBus = MessageBusManager.getMessageBus();
            if (messageBus != null) {
                MessageBusManager.getMessageBus().close();
            }
        } catch (JMSException e) {
            log.warn("Failed to shutdown messagebus connection", e);
        }
    }

    protected class OperationEventHandler implements EventHandler {
        private final ParallelOperationLimiter operationLimiter;
        public OperationEventHandler(ParallelOperationLimiter putLimiter) {
            this.operationLimiter = putLimiter;
        }

        @Override
        public void handleEvent(OperationEvent event) {
            if (event.getEventType().equals(OperationEvent.OperationEventType.COMPLETE)) {
                log.debug("Completed ingest of file " + event.getFileID());
                operationLimiter.removeJob(event.getFileID());
            } else if (event.getEventType().equals(OperationEvent.OperationEventType.FAILED)) {
                log.warn("Failed to ingest file " + event.getFileID() + ", Cause: " + event);
                resultCollector.addFailure(event.getFileID(), "Ingest failure", "BitrepositoryIngester", event.getInfo());
                operationLimiter.removeJob(event.getFileID());
            }
        }
    }

    /**
     * Provides functionality for limiting the number of operations by providing a addJob method which
     * will block if a specified limit is reached.
     */
    protected class ParallelOperationLimiter {
        private final BlockingQueue<String> activeOperations;
        private final int secondsToWaitForFinish;

        ParallelOperationLimiter(int limit, int timeToWaitForFinish) {
            activeOperations = new LinkedBlockingQueue<>(limit);
            this.secondsToWaitForFinish = timeToWaitForFinish;
        }

        /**
         * Will block until the if the activeOperations queue limit is exceeded and unblock when a job is remove.
         * @param fileID Used as ID for the job in the queue.
         */
        void addJob(String fileID) {
            try {
                activeOperations.put(fileID);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        void removeJob(String fileID) {
            activeOperations.remove(fileID);
        }

        public void waitForFinish() {
            int secondsWaiting = 0;
            while (!activeOperations.isEmpty() && secondsWaiting++ < secondsToWaitForFinish) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    //No problem
                }
            }
            if (secondsWaiting > secondsToWaitForFinish) {
                String message = "Timeout(" + secondsToWaitForFinish+ "s) waiting for last files (" + Arrays.toString(activeOperations.toArray()) + ")to complete.";
                log.warn(message);
                for (String fileID : activeOperations.toArray(new String[activeOperations.size()])) {
                    resultCollector.addFailure(fileID, "Ingest failure", "BitrepositoryIngester",
                            "Timeout waiting for last files to be ingested.");
                }
            }
        }
    }
}
