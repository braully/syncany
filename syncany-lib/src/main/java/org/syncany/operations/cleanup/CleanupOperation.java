/*
 * Syncany, www.syncany.org
 * Copyright (C) 2011-2014 Philipp C. Heckel <philipp.heckel@gmail.com> 
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.syncany.operations.cleanup;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.simpleframework.xml.core.Persister;
import org.syncany.chunk.Chunk;
import org.syncany.chunk.MultiChunk;
import org.syncany.config.Config;
import org.syncany.config.to.CleanupTO;
import org.syncany.database.DatabaseVersion;
import org.syncany.database.FileContent;
import org.syncany.database.FileVersion;
import org.syncany.database.MultiChunkEntry;
import org.syncany.database.MultiChunkEntry.MultiChunkId;
import org.syncany.database.PartialFileHistory;
import org.syncany.database.PartialFileHistory.FileHistoryId;
import org.syncany.database.SqlDatabase;
import org.syncany.database.dao.DatabaseXmlSerializer;
import org.syncany.operations.AbstractTransferOperation;
import org.syncany.operations.cleanup.CleanupOperationResult.CleanupResultCode;
import org.syncany.operations.down.DownOperation;
import org.syncany.operations.ls_remote.LsRemoteOperation;
import org.syncany.operations.ls_remote.LsRemoteOperationResult;
import org.syncany.operations.status.StatusOperation;
import org.syncany.operations.status.StatusOperationResult;
import org.syncany.operations.up.UpOperation;
import org.syncany.plugins.transfer.RemoteTransaction;
import org.syncany.plugins.transfer.StorageException;
import org.syncany.plugins.transfer.files.CleanupRemoteFile;
import org.syncany.plugins.transfer.files.DatabaseRemoteFile;
import org.syncany.plugins.transfer.files.MultichunkRemoteFile;
import org.syncany.plugins.transfer.files.RemoteFile;

/**
 * The purpose of the cleanup operation is to keep the local database and the
 * remote repository clean -- thereby allowing it to be used indefinitely without
 * any performance issues or storage shortage.
 * 
 * <p>The responsibilities of the cleanup operations include:
 * <ul>
 *   <li>Remove old {@link FileVersion} and their corresponding database entities.
 *       In particular, it also removes {@link PartialFileHistory}s, {@link FileContent}s,
 *       {@link Chunk}s and {@link MultiChunk}s.</li>
 *   <li>Merge metadata of a single client and remove old database version files
 *       from the remote storage.</li>   
 * </ul>
 * 
 * <p>High level strategy:
 * <ul>
 *    <ol>Lock repo and start thread that renews the lock every X seconds</ol>
 *    <ol>Find old versions / contents / ... from database</ol>
 *    <ol>Write and upload old versions to PRUNE file</ol>
 *    <ol>Remotely delete unused multichunks</ol> 
 *    <ol>Stop lock renewal thread and unlock repo</ol>
 * </ul>
 * 
 * <p><b>Important issues:</b>
 * All remote operations MUST check if the lock has been recently renewed. If it hasn't, the connection has been lost.
 * 
 * @author Philipp C. Heckel <philipp.heckel@gmail.com>
 */
public class CleanupOperation extends AbstractTransferOperation {
	private static final Logger logger = Logger.getLogger(CleanupOperation.class.getSimpleName());

	public static final String ACTION_ID = "cleanup";
	private static final int BEFORE_DOUBLE_CHECK_TIME = 1200;

	private CleanupOperationOptions options;
	private CleanupOperationResult result;

	private SqlDatabase localDatabase;
	private RemoteTransaction remoteTransaction;

	public CleanupOperation(Config config) {
		this(config, new CleanupOperationOptions());
	}

	public CleanupOperation(Config config, CleanupOperationOptions options) {
		super(config, ACTION_ID);

		this.options = options;
		this.result = new CleanupOperationResult();
		this.localDatabase = new SqlDatabase(config);
	}

	@Override
	public CleanupOperationResult execute() throws Exception {
		logger.log(Level.INFO, "");
		logger.log(Level.INFO, "Running 'Cleanup' at client " + config.getMachineName() + " ...");
		logger.log(Level.INFO, "--------------------------------------------");

		// Do initial check out remote repository preconditions
		CleanupResultCode preconditionResult = checkPreconditions();

		if (preconditionResult != CleanupResultCode.OK) {
			return new CleanupOperationResult(preconditionResult);
		}

		startOperation();

		// If there are any, rollback any existing/old transactions.
		// If other clients have unfinished transactions with deletions, do not proceed.
		boolean blockingTransactionExist = !transferManager.cleanTransactions();
		if (blockingTransactionExist) {
			finishOperation();
			return new CleanupOperationResult(CleanupResultCode.NOK_REPO_BLOCKED);
		}

		// Wait two seconds (conservative cleanup, see #104)
		logger.log(Level.INFO, "Cleanup: Waiting a while to be sure that no other actions are running ...");
		Thread.sleep(BEFORE_DOUBLE_CHECK_TIME);

		// Check again
		preconditionResult = checkPreconditions();

		if (preconditionResult != CleanupResultCode.OK) {
			finishOperation();
			return new CleanupOperationResult(preconditionResult);
		}

		logger.log(Level.INFO, "Cleanup: Starting transaction.");
		remoteTransaction = new RemoteTransaction(config, transferManager);
		// Now do the actual work!

		if (options.isRemoveOldVersions()) {
			removeOldVersions();
		}

		if (options.isRemoveUnreferencedTemporaryFiles()) {
			transferManager.removeUnreferencedTemporaryFiles();
		}

		mergeRemoteFiles();

		finishOperation();

		return updateResultCode(result);
	}

	private CleanupOperationResult updateResultCode(CleanupOperationResult result) {
		if (result.getMergedDatabaseFilesCount() > 0 || result.getRemovedMultiChunks().size() > 0 || result.getRemovedOldVersionsCount() > 0) {
			result.setResultCode(CleanupResultCode.OK);
		}
		else {
			result.setResultCode(CleanupResultCode.OK_NOTHING_DONE);
		}

		return result;
	}

	private CleanupResultCode checkPreconditions() throws Exception {
		if (hasDirtyDatabaseVersions()) {
			return CleanupResultCode.NOK_DIRTY_LOCAL;
		}

		if (!options.isForce() && wasCleanedRecently()) {
			return CleanupResultCode.NOK_RECENTLY_CLEANED;
		}

		if (hasLocalChanges()) {
			return CleanupResultCode.NOK_LOCAL_CHANGES;
		}

		if (hasRemoteChanges()) {
			return CleanupResultCode.NOK_REMOTE_CHANGES;
		}

		if (otherRemoteOperationsRunning(CleanupOperation.ACTION_ID, UpOperation.ACTION_ID, DownOperation.ACTION_ID)) {
			return CleanupResultCode.NOK_OTHER_OPERATIONS_RUNNING;
		}

		return CleanupResultCode.OK;
	}

	private boolean hasLocalChanges() throws Exception {
		StatusOperationResult statusOperationResult = new StatusOperation(config, options.getStatusOptions()).execute();
		return statusOperationResult.getChangeSet().hasChanges();
	}

	private void removeOldVersions() throws Exception {
		Map<FileHistoryId, FileVersion> purgeFileVersions = new HashMap<>();

		purgeFileVersions.putAll(localDatabase.getFileHistoriesWithMaxPurgeVersion(options.getKeepVersionsCount()));
		purgeFileVersions.putAll(localDatabase.getDeletedFileVersions());

		boolean needToRemoveFileVersions = purgeFileVersions.size() > 0;

		if (!needToRemoveFileVersions) {
			logger.log(Level.INFO, "- Old version removal: Not necessary (no file histories longer than {0} versions found).",
					options.getKeepVersionsCount());
			return;
		}

		logger.log(Level.INFO, "- Old version removal: Found {0} file histories that need cleaning (longer than {1} versions).", new Object[] {
				purgeFileVersions.size(), options.getKeepVersionsCount() });

		// Local: First, remove file versions that are not longer needed
		localDatabase.removeSmallerOrEqualFileVersions(purgeFileVersions);

		// Local: Then, determine what must be changed remotely and remove it locally
		Map<MultiChunkId, MultiChunkEntry> unusedMultiChunks = localDatabase.getUnusedMultiChunks();

		localDatabase.removeUnreferencedDatabaseEntities();
		remoteDeleteUnusedMultiChunks(unusedMultiChunks);

		// Update stats
		result.setRemovedOldVersionsCount(purgeFileVersions.size());
		result.setRemovedMultiChunks(unusedMultiChunks);
	}

	private void remoteDeleteUnusedMultiChunks(Map<MultiChunkId, MultiChunkEntry> unusedMultiChunks) throws StorageException {
		logger.log(Level.INFO, "- Deleting remote multichunks ...");

		for (MultiChunkEntry multiChunkEntry : unusedMultiChunks.values()) {
			logger.log(Level.FINE, "  + Deleting remote multichunk " + multiChunkEntry + " ...");
			remoteTransaction.delete(new MultichunkRemoteFile(multiChunkEntry.getId()));
		}
	}

	private boolean hasDirtyDatabaseVersions() {
		Iterator<DatabaseVersion> dirtyDatabaseVersions = localDatabase.getDirtyDatabaseVersions();
		return dirtyDatabaseVersions.hasNext(); // TODO [low] Is this a resource creeper?
	}

	private boolean hasRemoteChanges() throws Exception {
		LsRemoteOperationResult lsRemoteOperationResult = new LsRemoteOperation(config).execute();
		return lsRemoteOperationResult.getUnknownRemoteDatabases().size() > 0;
	}

	private boolean wasCleanedRecently() throws Exception {
		return getCleanupTO().getLastTimeCleaned() + options.getMinSecondsBetweenCleanups() > System.currentTimeMillis() / 1000;
	}

	private void mergeRemoteFiles() throws Exception {
		// Retrieve all database versions
		Map<String, List<DatabaseRemoteFile>> allDatabaseFilesMap = retrieveAllRemoteDatabaseFiles();

		boolean needMerge = needMerge(allDatabaseFilesMap);

		if (!needMerge) {
			logger.log(Level.INFO, "- No purging happened. Number of database files does not exceed threshold. Not merging remote files.");
			return;
		}

		// Now do the merge!
		logger.log(Level.INFO, "- Merge remote files ...");

		List<DatabaseRemoteFile> allToDeleteDatabaseFiles = new ArrayList<DatabaseRemoteFile>();
		Map<File, DatabaseRemoteFile> allMergedDatabaseFiles = new TreeMap<File, DatabaseRemoteFile>();

		for (String client : allDatabaseFilesMap.keySet()) {
			List<DatabaseRemoteFile> clientDatabaseFiles = allDatabaseFilesMap.get(client);
			Collections.sort(clientDatabaseFiles);
			logger.log(Level.INFO, "Databases: " + clientDatabaseFiles);

			// 1. Determine files to delete remotely
			List<DatabaseRemoteFile> toDeleteDatabaseFiles = new ArrayList<DatabaseRemoteFile>(clientDatabaseFiles);
			allToDeleteDatabaseFiles.addAll(toDeleteDatabaseFiles);

			// 2. Write new database file and save it in allMergedDatabaseFiles
			writeMergeFile(client, clientDatabaseFiles.get(0).getClientVersion(), allMergedDatabaseFiles);

		}
		rememberDatabases(allMergedDatabaseFiles);
		// 3. Prepare transaction

		// Queue old databases for deletion
		for (RemoteFile toDeleteRemoteFile : allToDeleteDatabaseFiles) {
			logger.log(Level.INFO, "   + Deleting remote file " + toDeleteRemoteFile + " ...");
			remoteTransaction.delete(toDeleteRemoteFile);
		}

		// Queue new databases for uploading
		for (File lastLocalMergeDatabaseFile : allMergedDatabaseFiles.keySet()) {
			RemoteFile lastRemoteMergeDatabaseFile = allMergedDatabaseFiles.get(lastLocalMergeDatabaseFile);

			logger.log(Level.INFO, "   + Uploading new file {0} from local file {1} ...", new Object[] { lastRemoteMergeDatabaseFile,
					lastLocalMergeDatabaseFile });

			remoteTransaction.upload(lastLocalMergeDatabaseFile, lastRemoteMergeDatabaseFile);
		}

		finishMerging();

		// Update stats
		result.setMergedDatabaseFilesCount(allToDeleteDatabaseFiles.size());
	}

	private boolean needMerge(Map<String, List<DatabaseRemoteFile>> allDatabaseFilesMap) {
		int numberOfDatabaseFiles = 0;

		for (String client : allDatabaseFilesMap.keySet()) {
			numberOfDatabaseFiles += allDatabaseFilesMap.get(client).size();
		}

		// A client will merge databases if the number of databases exceeds the maximum number per client times the amount of clients
		int maxDatabaseFiles = options.getMaxDatabaseFiles() * allDatabaseFilesMap.keySet().size();
		boolean tooManyDatabaseFiles = numberOfDatabaseFiles > maxDatabaseFiles;
		boolean removedOldVersions = result.getRemovedOldVersionsCount() > 0;

		return removedOldVersions || tooManyDatabaseFiles;
	}

	private void writeMergeFile(String clientName, long firstClientVersion, Map<File, DatabaseRemoteFile> allMergedDatabaseFiles)
			throws StorageException, IOException {

		// Increment the version by 1, to signal cleanup has occurred

		long lastClientVersion = getNewestDatabaseFileVersion(clientName, localDatabase.getKnownDatabases());
		DatabaseRemoteFile newRemoteMergeDatabaseFile = new DatabaseRemoteFile(clientName, lastClientVersion + 1);

		File newLocalMergeDatabaseFile = config.getCache().getDatabaseFile(newRemoteMergeDatabaseFile.getName());

		logger.log(Level.INFO, "   + Writing new merge file (from {0}, to {1}) to {2} ...", new Object[] {
				firstClientVersion, lastClientVersion, newLocalMergeDatabaseFile });

		Iterator<DatabaseVersion> lastNDatabaseVersions = localDatabase.getDatabaseVersionsTo(clientName, lastClientVersion);

		DatabaseXmlSerializer databaseDAO = new DatabaseXmlSerializer(config.getTransformer());
		databaseDAO.save(lastNDatabaseVersions, newLocalMergeDatabaseFile);
		allMergedDatabaseFiles.put(newLocalMergeDatabaseFile, newRemoteMergeDatabaseFile);
	}

	private void rememberDatabases(Map<File, DatabaseRemoteFile> allMergedDatabaseFiles) throws SQLException {
		// Remember newly written files as so not to redownload them later.
		List<DatabaseRemoteFile> newRemoteMergeDatabaseFiles = new ArrayList<DatabaseRemoteFile>();
		newRemoteMergeDatabaseFiles.addAll(allMergedDatabaseFiles.values());

		logger.log(Level.INFO, "Files: " + newRemoteMergeDatabaseFiles);
		localDatabase.writeKnownRemoteDatabases(newRemoteMergeDatabaseFiles);
	}

	private void finishMerging() throws Exception {
		long cleanupNumber = updateCleanupFileInTransaction();
		try {
			logger.log(Level.INFO, "Cleanup: COMMITTING TX ...");

			remoteTransaction.commit();
			localDatabase.commit();
			CleanupTO cleanupTO = getCleanupTO();
			cleanupTO.setLastTimeCleaned(System.currentTimeMillis() / 1000);
			cleanupTO.setCleanupNumber(cleanupNumber);
			setCleanupTO(cleanupTO);

		}
		catch (StorageException e) {
			logger.log(Level.INFO, "Cleanup: FAILED TO COMMIT TX. Rolling back ...");

			localDatabase.rollback();
			throw e;
		}

		logger.log(Level.INFO, "Cleanup: SUCCESS COMMITTING TX.");
	}

	/**
	 * retrieveAllRemoteDatabaseFiles returns a Map with clientNames as keys and
	 * lists of corresponding DatabaseRemoteFiles as values.
	 */
	private Map<String, List<DatabaseRemoteFile>> retrieveAllRemoteDatabaseFiles() throws StorageException {
		TreeMap<String, List<DatabaseRemoteFile>> allDatabaseRemoteFilesMap = new TreeMap<String, List<DatabaseRemoteFile>>();
		Map<String, DatabaseRemoteFile> allDatabaseRemoteFiles = transferManager.list(DatabaseRemoteFile.class);

		for (Map.Entry<String, DatabaseRemoteFile> entry : allDatabaseRemoteFiles.entrySet()) {
			String clientName = entry.getValue().getClientName();
			if (allDatabaseRemoteFilesMap.get(clientName) == null) {
				allDatabaseRemoteFilesMap.put(clientName, new ArrayList<DatabaseRemoteFile>());
			}
			allDatabaseRemoteFilesMap.get(clientName).add(entry.getValue());
		}

		return allDatabaseRemoteFilesMap;
	}

	private CleanupTO getCleanupTO() throws Exception {
		CleanupTO cleanupTO;
		if (config.getCleanupFile().exists()) {
			cleanupTO = (new Persister()).read(CleanupTO.class, config.getCleanupFile());
		}
		else {
			cleanupTO = new CleanupTO();
		}
		return cleanupTO;
	}

	private void setCleanupTO(CleanupTO cleanupTO) throws Exception {
		(new Persister()).write(cleanupTO, config.getCleanupFile());
	}

	private long updateCleanupFileInTransaction() throws StorageException, IOException {
		// Find all existing cleanup files
		Map<String, CleanupRemoteFile> cleanupFiles = transferManager.list(CleanupRemoteFile.class);

		Pattern cleanupFilePattern = Pattern.compile("cleanup-([0-9]+)");

		// First number is 1 (we will add 1 at the end)
		int cleanupNumber = 0;
		// Find the number of the last cleanup
		for (String filename : cleanupFiles.keySet()) {
			Matcher matcher = cleanupFilePattern.matcher(filename);
			matcher.matches();
			cleanupNumber = Math.max(cleanupNumber, Integer.parseInt(matcher.group(1)));

			// Schedule any existing cleanup files for deletion
			remoteTransaction.delete(cleanupFiles.get(filename));
		}

		// Upload a new cleanup file that indicates changes
		File cleanupFile = config.getCache().createTempFile("cleanup");
		remoteTransaction.upload(cleanupFile, new CleanupRemoteFile(cleanupNumber + 1));
		return cleanupNumber + 1;
	}
}
