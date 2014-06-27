/*
 * Sleuth Kit Data Model
 *
 * Copyright 2012-2014 Basis Technology Corp.
 * Contact: carrier <at> sleuthkit <dot> org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *	 http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.sleuthkit.datamodel;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.sleuthkit.datamodel.TskData.ObjectType;
import org.sleuthkit.datamodel.BlackboardArtifact.ARTIFACT_TYPE;
import org.sleuthkit.datamodel.BlackboardAttribute.ATTRIBUTE_TYPE;
import org.sleuthkit.datamodel.SleuthkitJNI.CaseDbHandle.AddImageProcess;
import org.sleuthkit.datamodel.TskData.FileKnown;
import org.sleuthkit.datamodel.TskData.TSK_DB_FILES_TYPE_ENUM;
import org.sleuthkit.datamodel.TskData.TSK_FS_META_FLAG_ENUM;
import org.sleuthkit.datamodel.TskData.TSK_FS_META_TYPE_ENUM;
import org.sleuthkit.datamodel.TskData.TSK_FS_NAME_FLAG_ENUM;
import org.sleuthkit.datamodel.TskData.TSK_FS_NAME_TYPE_ENUM;
import org.sqlite.SQLiteJDBCLoader;

/**
 * Represents the case database and abstracts out the most commonly used
 * database operations.
 *
 * Also provides case database-level lock that protect access to the database
 * resource. The lock is available outside of the class to synchronize certain
 * actions (such as addition of an image) with concurrent database writes, for
 * database implementations (such as SQLite) that might need it.
 */
public class SleuthkitCase {

	// This must be the same as TSK_SCHEMA_VER in tsk/auto/db_sqlite.cpp.
	private static final int SCHEMA_VERSION_NUMBER = 3;		
		
	private static final int DATABASE_LOCKED_ERROR = 0;
	private static final int SQLITE_BUSY_ERROR = 5;
	private final ConnectionPerThreadDispenser connections; 		
	private final String dbPath;
	private final String dbDirPath;
	private int versionNumber;
	private String dbBackupPath = null;
	private volatile SleuthkitJNI.CaseDbHandle caseHandle;
	private final ResultSetHelper rsHelper = new ResultSetHelper(this);
	private int artifactIDcounter = 1001;
	private int attributeIDcounter = 1001;
	// for use by getCarvedDirectoryId method only
	private final Map<Long, Long> systemIdMap = new HashMap<Long, Long>();
	
	// cache for file system results
	private final Map<Long, FileSystem> fileSystemIdMap = new HashMap<Long, FileSystem>();
	
	//database lock
	private static final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock(true); //use fairness policy
		
	private static final Logger logger = Logger.getLogger(SleuthkitCase.class.getName());
	private final ArrayList<ErrorObserver> errorObservers = new ArrayList<ErrorObserver>();
	
	/**
	 * constructor (private) - client uses openCase() and newCase() instead
	 *
	 * @param dbPath path to the database file
	 * @param caseHandle handle to the case database API
	 * @throws SQLException thrown if SQL error occurred
	 * @throws ClassNotFoundException thrown if database driver could not be
	 * loaded
	 * @throws TskCoreException thrown if critical error occurred within TSK
	 * case
	 */
	private SleuthkitCase(String dbPath, SleuthkitJNI.CaseDbHandle caseHandle) throws SQLException, ClassNotFoundException, TskCoreException {
		Class.forName("org.sqlite.JDBC");
		this.dbPath = dbPath;
		this.dbDirPath = new java.io.File(dbPath).getParentFile().getAbsolutePath();
		this.caseHandle = caseHandle;
		this.connections = new ConnectionPerThreadDispenser();
		acquireExclusiveLock();
		try {
			CaseDbConnection connection = connections.getConnectionWithoutPreparedStatements();			
			configureDB(connection);
			initBlackboardArtifactTypes(connection);
			initBlackboardAttributeTypes(connection);
			updateDatabaseSchema(connection);
			connection.prepareStatements();
		} finally {
			releaseExclusiveLock();
		}
	}
	
	private void updateDatabaseSchema(CaseDbConnection connection) throws TskCoreException {
		acquireExclusiveLock();
		ResultSet resultSet = null;
		Statement statement = null;		
		try {			
			connection.beginTransaction();
						
			// Get the schema version number of the database from the tsk_db_info table.
			int schemaVersionNumber = SCHEMA_VERSION_NUMBER;
			statement = connection.createStatement();
			resultSet = connection.executeQuery(statement, "SELECT schema_ver FROM tsk_db_info");
			if (resultSet.next()) {
				schemaVersionNumber = resultSet.getInt("schema_ver");	
			}
			
			if (SCHEMA_VERSION_NUMBER != schemaVersionNumber) {
				// Make a backup copy of the database. Client code can get the path of the backup
				// using the getBackupDatabasePath() method.
				String backupFilePath = dbPath + ".schemaVer" + schemaVersionNumber + ".backup";
				copyCaseDB(backupFilePath);
				dbBackupPath = backupFilePath;
				
				// ***CALL SCHEMA UPDATE METHODS HERE***
				// Each method should examine the schema number passed to it and either:
				//    a. Do nothing and return the current schema version number, or
				//    b. Upgrade the database and then increment and return the current schema version number.
				schemaVersionNumber = updateFromSchema2toSchema3(schemaVersionNumber);		

				// Write the updated schema version number to the the tsk_db_info table.
				connection.executeUpdate("UPDATE tsk_db_info SET schema_ver = " + schemaVersionNumber);
			}
			versionNumber= schemaVersionNumber;
			connection.commitTransaction();
		}
		catch (Exception ex) { // Cannot do exception multi-catch in Java 6, so use catch-all
			try {
				connection.rollbackTransaction();
			}
			catch (SQLException e) {
				logger.log(Level.SEVERE, "Failed to rollback failed database schema update", e);
			}
			throw new TskCoreException("Failed to update database schema", ex);
		}
		finally {
			closeResultSet(resultSet);
			closeStatement(statement);
			releaseExclusiveLock();
		}
	}
		
	private int updateFromSchema2toSchema3(int schemaVersionNumber) throws SQLException, TskCoreException {
		if (schemaVersionNumber != 2) {
			return schemaVersionNumber;
		}

		Statement statement = null;
		try {
			CaseDbConnection connection = connections.getConnection();		

			// Add new tables for tags.
			statement = connection.createStatement();
			statement.execute("CREATE TABLE tag_names (tag_name_id INTEGER PRIMARY KEY, display_name TEXT UNIQUE, description TEXT NOT NULL, color TEXT NOT NULL)");
			statement.execute("CREATE TABLE content_tags (tag_id INTEGER PRIMARY KEY, obj_id INTEGER NOT NULL, tag_name_id INTEGER NOT NULL, comment TEXT NOT NULL, begin_byte_offset INTEGER NOT NULL, end_byte_offset INTEGER NOT NULL)");
			statement.execute("CREATE TABLE blackboard_artifact_tags (tag_id INTEGER PRIMARY KEY, artifact_id INTEGER NOT NULL, tag_name_id INTEGER NOT NULL, comment TEXT NOT NULL)");

			// Add new table for reports
			statement.execute("CREATE TABLE reports (report_id INTEGER PRIMARY KEY, path TEXT NOT NULL, crtime INTEGER NOT NULL, src_module_name TEXT NOT NULL, report_name TEXT NOT NULL)");

			// Add columns to existing tables
			statement.execute("ALTER TABLE tsk_image_info ADD COLUMN size INTEGER;");
			statement.execute("ALTER TABLE tsk_image_info ADD COLUMN md5 TEXT;");
			statement.execute("ALTER TABLE tsk_image_info ADD COLUMN display_name TEXT;");
			statement.execute("ALTER TABLE tsk_fs_info ADD COLUMN display_name TEXT;");
			statement.execute("ALTER TABLE tsk_files ADD COLUMN meta_seq INTEGER;");

			// Make the prepared statements available for use in migrating legacy data.
			// THIS IS ONLY GUARANTEED TO WORK FOR GOING FROM VERSION 2 to VERSION 3.
			// FIX THIS BEFORE ADDING VERSION 4 CODE.
			connection.prepareStatements();

			// This data structure is used to keep track of the unique tag names 
			// created from the TSK_TAG_NAME attributes of the now obsolete 
			// TSK_TAG_FILE and TSK_TAG_ARTIFACT artifacts.
			HashMap<String, TagName> tagNames = new HashMap<String, TagName>();

			// Convert TSK_TAG_FILE artifacts into content tags. Leave the artifacts behind as a backup.
			for (BlackboardArtifact artifact : getBlackboardArtifacts(ARTIFACT_TYPE.TSK_TAG_FILE)) {
				Content content = getContentById(artifact.getObjectID());
				String name = "";
				String comment = "";
				ArrayList<BlackboardAttribute> attributes = getBlackboardAttributes(artifact);
				for (BlackboardAttribute attribute : attributes) {
					if (attribute.getAttributeTypeID() == ATTRIBUTE_TYPE.TSK_TAG_NAME.getTypeID()) {
						name = attribute.getValueString();
					}
					else if (attribute.getAttributeTypeID() == ATTRIBUTE_TYPE.TSK_COMMENT.getTypeID()) {
						comment = attribute.getValueString();
					}
				}

				if (!name.isEmpty()) {
					TagName tagName;
					if (tagNames.containsKey(name)) {
						tagName = tagNames.get(name);
					}
					else {
						tagName = addTagName(name, "", TagName.HTML_COLOR.NONE);
						tagNames.put(name, tagName);
					}
					addContentTag(content, tagName, comment, 0, content.getSize() - 1);
				}
			}

			// Convert TSK_TAG_ARTIFACT artifacts into blackboard artifact tags. Leave the artifacts behind as a backup.
			for (BlackboardArtifact artifact : getBlackboardArtifacts(ARTIFACT_TYPE.TSK_TAG_ARTIFACT)) {
				String name = "";
				String comment = "";
				ArrayList<BlackboardAttribute> attributes = getBlackboardAttributes(artifact);
				for (BlackboardAttribute attribute : attributes) {
					if (attribute.getAttributeTypeID() == ATTRIBUTE_TYPE.TSK_TAG_NAME.getTypeID()) {
						name = attribute.getValueString();
					}
					else if (attribute.getAttributeTypeID() == ATTRIBUTE_TYPE.TSK_COMMENT.getTypeID()) {
						comment = attribute.getValueString();
					}
				}

				if (!name.isEmpty()) {
					TagName tagName;
					if (tagNames.containsKey(name)) {
						tagName = tagNames.get(name);
					}
					else {
						tagName = addTagName(name, "", TagName.HTML_COLOR.NONE);
						tagNames.put(name, tagName);
					}
					addBlackboardArtifactTag(artifact, tagName, comment);
				}
			}		

			connection.closePreparedStatements();
			
			return 3;	
		} finally {
			if (null != statement) {
				statement.close(); 
			}
		}
	}			
		
	/**
	 * Returns the path of a backup copy of the database made when a schema 
	 * version upgrade has occurred.
	 * @return The path of the backup file or null if no backup was made.
	 */
	public String getBackupDatabasePath() {
		return dbBackupPath;
	}
	
	/**
	 * create a new transaction: lock the database and set auto-commit false.
	 * this transaction should be passed to methods who take a transaction and
	 * then have transaction.commit() invoked on it to commit changes and unlock
	 * the database
	 *
	 * @return
	 * @throws TskCoreException
	 */
	public LogicalFileTransaction createTransaction() throws TskCoreException {
		try {
			CaseDbConnection connection = connections.getConnection();		
			return LogicalFileTransaction.startTransaction(connection.getConnection());
		} catch (SQLException ex) {
			Logger.getLogger(SleuthkitCase.class.getName()).log(Level.SEVERE, "failed to create transaction", ex);
			throw new TskCoreException("Failed to create transaction", ex);
		}
	}

	/**
	 * Get location of the database directory
	 *
	 * @return absolute database directory path
	 */
	public String getDbDirPath() {
		return dbDirPath;
	}
		
	private void configureDB(CaseDbConnection connection) throws TskCoreException {
		acquireExclusiveLock();
		Statement statement = null;
		try {
			// this should match SleuthkitJNI connection setup
			statement = connection.createStatement();			
			statement.execute("PRAGMA synchronous = OFF;"); //reduce i/o operations, we have no OS crash recovery anyway			
			statement.execute("PRAGMA read_uncommitted = True;"); //allow to query while in transaction - no need read locks
			statement.execute("PRAGMA foreign_keys = ON;");
			logger.log(Level.INFO, String.format("sqlite-jdbc version %s loaded in %s mode",
					SQLiteJDBCLoader.getVersion(), SQLiteJDBCLoader.isNativeMode()
					? "native" : "pure-java"));
		} catch (SQLException e) {
			throw new TskCoreException("Couldn't configure the database connection", e);
		} catch (Exception e) {
			throw new TskCoreException("Couldn't configure the database connection", e);
		} finally {
			closeStatement(statement);
			releaseExclusiveLock();
		}
	}

	// RJCTODO: Update comment, why is this public?
	/**
	 * Lock to protect against concurrent write accesses to case database and to
	 * block readers while database is in write transaction. Should be utilized
     * by all connection code where underlying storage supports max. 1 concurrent writer
     * MUST always call dbWriteUnLock() as early as possible, in the same thread
     * where acquireExclusiveLock() was called.
	 */
	public static void acquireExclusiveLock() {
		rwLock.writeLock().lock();
	}

	// RJCTODO: Update comment, why is this public?
	/**
	 * Release previously acquired write lock acquired in this thread using
     * acquireExclusiveLock(). Call in "finally" block to ensure the lock is always
	 * released.
	 */
	public static void releaseExclusiveLock() {
		rwLock.writeLock().unlock();
	}

	// RJCTODO: Update comment, why is this public?
	/**
	 * Lock to protect against read while it is in a write transaction state.
	 * Supports multiple concurrent readers if there is no writer. MUST always
     * call dbReadUnLock() as early as possible, in the same thread where
     * acquireSharedLock() was called.
	 */
	static void acquireSharedLock() {
		rwLock.readLock().lock();
	}

	// RJCTODO: Update comment, why is this public?
	/**
	 * Release previously acquired read lock acquired in this thread using
     * acquireSharedLock(). Call in "finally" block to ensure the lock is always
	 * released.
	 */
	static void releaseSharedLock() {
		rwLock.readLock().unlock();
	}

	/**
	 * Open an existing case
	 *
	 * @param dbPath Path to SQLite database.
	 * @return Case object
	 * @throws org.sleuthkit.datamodel.TskCoreException
	 */
	public static SleuthkitCase openCase(String dbPath) throws TskCoreException {
		SleuthkitCase.acquireExclusiveLock();
		final SleuthkitJNI.CaseDbHandle caseHandle = SleuthkitJNI.openCaseDb(dbPath);
		try {
			return new SleuthkitCase(dbPath, caseHandle);
		} catch (SQLException ex) {
			throw new TskCoreException("Couldn't open case at " + dbPath, ex);
		} catch (ClassNotFoundException ex) {
			throw new TskCoreException("Couldn't open case at " + dbPath, ex);
		} finally {
			SleuthkitCase.releaseExclusiveLock();
		}
	}

	/**
	 * Create a new case
	 *
	 * @param dbPath Path to where SQlite database should be created.
	 * @return Case object
	 * @throws org.sleuthkit.datamodel.TskCoreException
	 */
	public static SleuthkitCase newCase(String dbPath) throws TskCoreException {
		SleuthkitCase.acquireExclusiveLock();
		SleuthkitJNI.CaseDbHandle caseHandle = SleuthkitJNI.newCaseDb(dbPath);
		try {
			return new SleuthkitCase(dbPath, caseHandle);
		} catch (SQLException ex) {
			throw new TskCoreException("Couldn't open case at " + dbPath, ex);
		} catch (ClassNotFoundException ex) {
			throw new TskCoreException("Couldn't open case at " + dbPath, ex);
		} finally {
			SleuthkitCase.releaseExclusiveLock();
		}			
	}

	private void initBlackboardArtifactTypes(CaseDbConnection connection) throws SQLException, TskCoreException {
		acquireExclusiveLock();
		Statement s = null;				
		ResultSet rs = null;
		try {
			s = connection.createStatement();
			for (ARTIFACT_TYPE type : ARTIFACT_TYPE.values()) {
				rs = connection.executeQuery(s, "SELECT * from blackboard_artifact_types WHERE artifact_type_id = '" + type.getTypeID() + "'");
				if (!rs.next()) {
					addBuiltInArtifactType(type);
				}
				closeResultSet(rs);
			}
		} finally {
			closeResultSet(rs);
			closeStatement(s);
			releaseExclusiveLock();
		}
	}
	
	private void initBlackboardAttributeTypes(CaseDbConnection connection) throws SQLException, TskCoreException {
		acquireExclusiveLock();
		Statement s = null;				
		ResultSet rs = null;
		try {
			s = connection.createStatement();
			for (ATTRIBUTE_TYPE type : ATTRIBUTE_TYPE.values()) {
				rs = connection.executeQuery(s, "SELECT * from blackboard_attribute_types WHERE attribute_type_id = '" + type.getTypeID() + "'");
				if (!rs.next()) {
					addBuiltInAttrType(type);
				}
			}
		} finally {
			closeResultSet(rs);
			closeStatement(s);
			releaseExclusiveLock();
		}
	}

	/**
	 * Start process of adding an image to the case. Adding an image is a
	 * multi-step process and this returns an object that allows it to happen.
	 *
	 * @param timezone TZ timezone string to use for ingest of image.
	 * @param processUnallocSpace set to true if to process unallocated space on
	 * the image
	 * @param noFatFsOrphans true if to skip processing orphans on FAT
	 * filesystems
	 * @return object to start ingest
	 */
	public AddImageProcess makeAddImageProcess(String timezone, boolean processUnallocSpace, boolean noFatFsOrphans) {
		return this.caseHandle.initAddImageProcess(timezone, processUnallocSpace, noFatFsOrphans);
	}
	
	/**
	 * Get the list of root objects, meaning image files or local files virtual
	 * dir container.
	 *
	 * @return list of content objects.
	 * @throws TskCoreException exception thrown if a critical error occurs
	 * within tsk core
	 */
	public List<Content> getRootObjects() throws TskCoreException {
		acquireSharedLock();
		Statement s = null;				
		ResultSet rs = null;		
		try {
			CaseDbConnection connection = connections.getConnection();			
			s = connection.createStatement();
			rs = connection.executeQuery(s, "SELECT obj_id, type from tsk_objects "
					+ "WHERE par_obj_id IS NULL");			
			Collection<ObjectInfo> infos = new ArrayList<ObjectInfo>();
			while (rs.next()) {
				infos.add(new ObjectInfo(rs.getLong("obj_id"), ObjectType.valueOf(rs.getShort("type"))));
			}
			
			List<Content> rootObjs = new ArrayList<Content>();			
			for (ObjectInfo i : infos) {
				if (i.type == ObjectType.IMG) {
					rootObjs.add(getImageById(i.id));
				} else if (i.type == ObjectType.ABSTRACTFILE) {
					//check if virtual dir for local files
					AbstractFile af = getAbstractFileById(i.id);
					if (af instanceof VirtualDirectory) {
						rootObjs.add(af);
					} else {
						throw new TskCoreException("Parentless object has wrong type to be a root (ABSTRACTFILE, but not VIRTUAL_DIRECTORY: " + i.type);
					}
				} else {
					throw new TskCoreException("Parentless object has wrong type to be a root: " + i.type);
				}
			}			
			return rootObjs;
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting root objects.", ex);
		} finally {
			closeResultSet(rs);
			closeStatement(s);
			releaseSharedLock();
		}
	}

	/**
	 * Get all blackboard artifacts of a given type
	 *
	 * @param artifactTypeID artifact type id (must exist in database)
	 * @return list of blackboard artifacts
	 * @throws TskCoreException exception thrown if a critical error occurs
	 * within tsk core
	 */
	public ArrayList<BlackboardArtifact> getBlackboardArtifacts(int artifactTypeID) throws TskCoreException {
		acquireSharedLock();
		ResultSet rs = null;
		try {
			String artifactTypeName = getArtifactTypeString(artifactTypeID);
			CaseDbConnection connection = connections.getConnection();
			PreparedStatement statement = connection.getPreparedStatement(CaseDbConnection.PREPARED_STATEMENT.SELECT_ARTIFACTS_BY_TYPE);
			statement.clearParameters();
			statement.setInt(1, artifactTypeID);
			rs = connection.executeQuery(statement);		
			ArrayList<BlackboardArtifact> artifacts = new ArrayList<BlackboardArtifact>();
			while (rs.next()) {
				artifacts.add(new BlackboardArtifact(this, rs.getLong(1), rs.getLong(2),
						artifactTypeID, artifactTypeName, ARTIFACT_TYPE.fromID(artifactTypeID).getDisplayName()));
			}
			return artifacts;
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting or creating a blackboard artifact. " + ex.getMessage(), ex);
		} finally {
			closeResultSet(rs);
			releaseSharedLock();
		}
	}

	/**
	 * Get count of blackboard artifacts for a given content
	 *
	 * @param objId associated object
	 * @return count of artifacts
	 * @throws TskCoreException exception thrown if a critical error occurs
	 * within tsk core
	 */
	public long getBlackboardArtifactsCount(long objId) throws TskCoreException {
		acquireSharedLock();
		ResultSet rs = null;
		try {
			CaseDbConnection connection = connections.getConnection();
			PreparedStatement statement = connection.getPreparedStatement(CaseDbConnection.PREPARED_STATEMENT.COUNT_ARTIFACTS_FROM_SOURCE);		
			statement.clearParameters();			
			statement.setLong(1, objId);
			rs = connection.executeQuery(statement);		
			long count = 0;
			if (rs.next()) {
				count = rs.getLong(1);
			} else {
				throw new TskCoreException("Error getting count of artifacts by content. ");
			}
			return count;
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting number of blackboard artifacts by content. " + ex.getMessage(), ex);
		} finally {
			closeResultSet(rs);
			releaseSharedLock();
		}
	}

	/**
	 * Get count of blackboard artifacts of a given type
	 *
	 * @param artifactTypeID artifact type id (must exist in database)
	 * @return count of artifacts
	 * @throws TskCoreException exception thrown if a critical error occurs
	 * within tsk core
	 */
	public long getBlackboardArtifactsTypeCount(int artifactTypeID) throws TskCoreException {
		acquireSharedLock();
		ResultSet rs = null;
		try {
			CaseDbConnection connection = connections.getConnection();
			PreparedStatement statement = connection.getPreparedStatement(CaseDbConnection.PREPARED_STATEMENT.COUNT_ARTIFACTS_OF_TYPE);	
			statement.clearParameters();
			statement.setInt(1, artifactTypeID);
			rs = connection.executeQuery(statement);		
			long count = 0;
			if (rs.next()) {
				count = rs.getLong(1);
			} else {
				throw new TskCoreException("Error getting count of artifacts by type. ");
			}
			return count;
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting number of blackboard artifacts by type. " + ex.getMessage(), ex);
		} finally {
			closeResultSet(rs);
			releaseSharedLock();
		}
	}

	/**
	 * Helper to iterate over blackboard artifacts result set containing all
	 * columns and return a list of artifacts in the set. Must be enclosed in
     * acquireSharedLock. Result set and s must be freed by the caller.
	 *
	 * @param rs existing, active result set (not closed by this method)
	 * @return a list of blackboard artifacts in the result set
	 * @throws SQLException if result set could not be iterated upon
	 */
	private List<BlackboardArtifact> getArtifactsHelper(ResultSet rs) throws SQLException {
		ArrayList<BlackboardArtifact> artifacts = new ArrayList<BlackboardArtifact>();		
		while (rs.next()) {
			final int artifactTypeID = rs.getInt(3);
			final ARTIFACT_TYPE artType = ARTIFACT_TYPE.fromID(artifactTypeID);
			artifacts.add(new BlackboardArtifact(this, rs.getLong(1), rs.getLong(2),
					artifactTypeID, artType.getLabel(), artType.getDisplayName()));
		}		
		return artifacts;
	}

	/**
	 * Get all blackboard artifacts that have an attribute of the given type and
	 * String value
	 *
	 * @param attrType attribute of this attribute type to look for in the
	 * artifacts
	 * @param value value of the attribute of the attrType type to look for
	 * @return a list of blackboard artifacts with such an attribute
	 * @throws TskCoreException exception thrown if a critical error occurred
	 * within tsk core and artifacts could not be queried
	 */
	public List<BlackboardArtifact> getBlackboardArtifacts(BlackboardAttribute.ATTRIBUTE_TYPE attrType, String value) throws TskCoreException {
		acquireSharedLock();
		Statement s = null;				
		ResultSet rs = null;
		try {
			CaseDbConnection connection = connections.getConnection();		
			s = connection.createStatement();			
			rs = connection.executeQuery(s, "SELECT DISTINCT blackboard_artifacts.artifact_id, "
					+ "blackboard_artifacts.obj_id, blackboard_artifacts.artifact_type_id "
					+ "FROM blackboard_artifacts, blackboard_attributes "
					+ "WHERE blackboard_artifacts.artifact_id = blackboard_attributes.artifact_id "
					+ "AND blackboard_attributes.attribute_type_id IS " + attrType.getTypeID()
					+ " AND blackboard_attributes.value_text IS '" + value + "'");	
			return getArtifactsHelper(rs);
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting blackboard artifacts by attribute. " + ex.getMessage(), ex);
		} finally {
			closeResultSet(rs);
			closeStatement(s);
			releaseSharedLock();
		}
	}

	/**
	 * Get all blackboard artifacts that have an attribute of the given type and
	 * String value
	 *
	 * @param attrType attribute of this attribute type to look for in the
	 * artifacts
	 * @param subString value substring of the string attribute of the attrType
	 * type to look for
	 * @param startsWith if true, the artifact attribute string should start
	 * with the substring, if false, it should just contain it
	 * @return a list of blackboard artifacts with such an attribute
	 * @throws TskCoreException exception thrown if a critical error occurred
	 * within tsk core and artifacts could not be queried
	 */
	public List<BlackboardArtifact> getBlackboardArtifacts(BlackboardAttribute.ATTRIBUTE_TYPE attrType, String subString, boolean startsWith) throws TskCoreException {		
		subString = "%" + subString;
		if (startsWith == false) {
			subString = subString + "%";
		}		
		acquireSharedLock();
		Statement s = null;				
		ResultSet rs = null;		
		try {
			CaseDbConnection connection = connections.getConnection();			
			s = connection.createStatement();
			rs = connection.executeQuery(s, "SELECT DISTINCT blackboard_artifacts.artifact_id, "
					+ "blackboard_artifacts.obj_id, blackboard_artifacts.artifact_type_id "
					+ "FROM blackboard_artifacts, blackboard_attributes "
					+ "WHERE blackboard_artifacts.artifact_id = blackboard_attributes.artifact_id "
					+ "AND blackboard_attributes.attribute_type_id IS " + attrType.getTypeID()
					+ " AND blackboard_attributes.value_text LIKE '" + subString + "'");			
			return getArtifactsHelper(rs);
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting blackboard artifacts by attribute. " + ex.getMessage(), ex);
		} finally {
			closeResultSet(rs);	
			closeStatement(s);
			releaseSharedLock();
		}
	}

	/**
	 * Get all blackboard artifacts that have an attribute of the given type and
	 * integer value
	 *
	 * @param attrType attribute of this attribute type to look for in the
	 * artifacts
	 * @param value value of the attribute of the attrType type to look for
	 * @return a list of blackboard artifacts with such an attribute
	 * @throws TskCoreException exception thrown if a critical error occurred
	 * within tsk core and artifacts could not be queried
	 */
	public List<BlackboardArtifact> getBlackboardArtifacts(BlackboardAttribute.ATTRIBUTE_TYPE attrType, int value) throws TskCoreException {
		acquireSharedLock();
		Statement s = null;				
		ResultSet rs = null;
		try {
			CaseDbConnection connection = connections.getConnection();			
			s = connection.createStatement();
			rs = connection.executeQuery(s, "SELECT DISTINCT blackboard_artifacts.artifact_id, "
					+ "blackboard_artifacts.obj_id, blackboard_artifacts.artifact_type_id "
					+ "FROM blackboard_artifacts, blackboard_attributes "
					+ "WHERE blackboard_artifacts.artifact_id = blackboard_attributes.artifact_id "
					+ "AND blackboard_attributes.attribute_type_id IS " + attrType.getTypeID()
					+ " AND blackboard_attributes.value_int32 IS " + value);
			return getArtifactsHelper(rs);
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting blackboard artifacts by attribute. " + ex.getMessage(), ex);
		} finally {
			closeResultSet(rs);	
			closeStatement(s);
			releaseSharedLock();
		}
	}

	/**
	 * Get all blackboard artifacts that have an attribute of the given type and
	 * long value
	 *
	 * @param attrType attribute of this attribute type to look for in the
	 * artifacts
	 * @param value value of the attribute of the attrType type to look for
	 * @return a list of blackboard artifacts with such an attribute
	 * @throws TskCoreException exception thrown if a critical error occurred
	 * within tsk core and artifacts could not be queried
	 */
	public List<BlackboardArtifact> getBlackboardArtifacts(BlackboardAttribute.ATTRIBUTE_TYPE attrType, long value) throws TskCoreException {
		acquireSharedLock();
		Statement s = null;				
		ResultSet rs = null;
		try {
			CaseDbConnection connection = connections.getConnection();			
			s = connection.createStatement();
			rs = connection.executeQuery(s, "SELECT DISTINCT blackboard_artifacts.artifact_id, "
					+ "blackboard_artifacts.obj_id, blackboard_artifacts.artifact_type_id "
					+ "FROM blackboard_artifacts, blackboard_attributes "
					+ "WHERE blackboard_artifacts.artifact_id = blackboard_attributes.artifact_id "
					+ "AND blackboard_attributes.attribute_type_id IS " + attrType.getTypeID()
					+ " AND blackboard_attributes.value_int64 IS " + value);			
			return getArtifactsHelper(rs);
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting blackboard artifacts by attribute. " + ex.getMessage(), ex);
		} finally {
			closeResultSet(rs);
			closeStatement(s);
			releaseSharedLock();
		}
	}

	/**
	 * Get all blackboard artifacts that have an attribute of the given type and
	 * double value
	 *
	 * @param attrType attribute of this attribute type to look for in the
	 * artifacts
	 * @param value value of the attribute of the attrType type to look for
	 * @return a list of blackboard artifacts with such an attribute
	 * @throws TskCoreException exception thrown if a critical error occurred
	 * within tsk core and artifacts could not be queried
	 */
	public List<BlackboardArtifact> getBlackboardArtifacts(BlackboardAttribute.ATTRIBUTE_TYPE attrType, double value) throws TskCoreException {
		acquireSharedLock();
		Statement s = null;				
		ResultSet rs = null;
		try {
			CaseDbConnection connection = connections.getConnection();			
			s = connection.createStatement();
			rs = connection.executeQuery(s, "SELECT DISTINCT blackboard_artifacts.artifact_id, "
					+ "blackboard_artifacts.obj_id, blackboard_artifacts.artifact_type_id "
					+ "FROM blackboard_artifacts, blackboard_attributes "
					+ "WHERE blackboard_artifacts.artifact_id = blackboard_attributes.artifact_id "
					+ "AND blackboard_attributes.attribute_type_id IS " + attrType.getTypeID()
					+ " AND blackboard_attributes.value_double IS " + value);
			return getArtifactsHelper(rs);
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting blackboard artifacts by attribute. " + ex.getMessage(), ex);
		} finally {
			closeResultSet(rs);			
			closeStatement(s);
			releaseSharedLock();
		}
	}

	/**
	 * Get all blackboard artifacts that have an attribute of the given type and
	 * byte value
	 *
	 * @param attrType attribute of this attribute type to look for in the
	 * artifacts
	 * @param value value of the attribute of the attrType type to look for
	 * @return a list of blackboard artifacts with such an attribute
	 * @throws TskCoreException exception thrown if a critical error occurred
	 * within tsk core and artifacts could not be queried
	 */
	public List<BlackboardArtifact> getBlackboardArtifacts(BlackboardAttribute.ATTRIBUTE_TYPE attrType, byte value) throws TskCoreException {
		acquireSharedLock();
		Statement s = null;				
		ResultSet rs = null;
		try {
			CaseDbConnection connection = connections.getConnection();			
			s = connection.createStatement();
			rs = connection.executeQuery(s, "SELECT DISTINCT blackboard_artifacts.artifact_id, "
					+ "blackboard_artifacts.obj_id, blackboard_artifacts.artifact_type_id "
					+ "FROM blackboard_artifacts, blackboard_attributes "
					+ "WHERE blackboard_artifacts.artifact_id = blackboard_attributes.artifact_id "
					+ "AND blackboard_attributes.attribute_type_id IS " + attrType.getTypeID()
					+ " AND blackboard_attributes.value_byte IS " + value);
			return getArtifactsHelper(rs);
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting blackboard artifacts by attribute. " + ex.getMessage(), ex);
		} finally {
			closeResultSet(rs);			
			closeStatement(s);
			releaseSharedLock();
		}
	}

	/**
	 * Get _standard_ blackboard artifact types in use.  This does
     * not currently return user-defined ones. 
	 *
	 * @return list of blackboard artifact types
	 * @throws TskCoreException exception thrown if a critical error occurred
	 * within tsk core
	 */
	public ArrayList<BlackboardArtifact.ARTIFACT_TYPE> getBlackboardArtifactTypes() throws TskCoreException {
		acquireSharedLock();
		Statement s = null;				
		ResultSet rs = null;
		try {
			CaseDbConnection connection = connections.getConnection();			
			s = connection.createStatement();
			rs = connection.executeQuery(s, "SELECT artifact_type_id FROM blackboard_artifact_types");			
			ArrayList<BlackboardArtifact.ARTIFACT_TYPE> artifact_types = new ArrayList<BlackboardArtifact.ARTIFACT_TYPE>();
			while (rs.next()) {
                /*
                 * Only return ones in the enum because otherwise exceptions
                 * get thrown down the call stack. Need to remove use of enum
                 * for the attribute types */
				for (BlackboardArtifact.ARTIFACT_TYPE artType : BlackboardArtifact.ARTIFACT_TYPE.values()) {
					if (artType.getTypeID() == rs.getInt(1)) {
						artifact_types.add(artType);
					}
				}				
			}
			return artifact_types;
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting artifact types. " + ex.getMessage(), ex);
		} finally {
			closeResultSet(rs);			
			closeStatement(s);
			releaseSharedLock();
		}		
	}

	/**
	 * Get all of the blackboard artifact types that are in use in the
	 * blackboard.
	 *
	 * @return List of blackboard artifact types
	 * @throws TskCoreException
	 */
	public ArrayList<BlackboardArtifact.ARTIFACT_TYPE> getBlackboardArtifactTypesInUse() throws TskCoreException {
		// @@@ TODO: This should be rewritten as a single query. 
		
		ArrayList<BlackboardArtifact.ARTIFACT_TYPE> allArts = getBlackboardArtifactTypes();
		ArrayList<BlackboardArtifact.ARTIFACT_TYPE> usedArts = new ArrayList<BlackboardArtifact.ARTIFACT_TYPE>();
		
		for (BlackboardArtifact.ARTIFACT_TYPE art : allArts) {
			if (getBlackboardArtifactsTypeCount(art.getTypeID()) > 0) {
				usedArts.add(art);
			}
		}
		return usedArts;
	}

	/**
	 * Get all blackboard attribute types
	 *
	 * Gets both static (in enum) and dynamic attributes types (created by
	 * modules at runtime)
	 *
	 * @return list of blackboard attribute types
	 * @throws TskCoreException exception thrown if a critical error occurred
	 * within tsk core
	 */
	public ArrayList<BlackboardAttribute.ATTRIBUTE_TYPE> getBlackboardAttributeTypes() throws TskCoreException {
		acquireSharedLock();
		Statement s = null;				
		ResultSet rs = null;
		try {
			CaseDbConnection connection = connections.getConnection();			
			s = connection.createStatement();
			rs = connection.executeQuery(s, "SELECT type_name FROM blackboard_attribute_types");
			ArrayList<BlackboardAttribute.ATTRIBUTE_TYPE> attribute_types = new ArrayList<BlackboardAttribute.ATTRIBUTE_TYPE>();			
			while (rs.next()) {
				attribute_types.add(BlackboardAttribute.ATTRIBUTE_TYPE.fromLabel(rs.getString(1)));
			}
			return attribute_types;
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting attribute types. " + ex.getMessage(), ex);
		} finally {
			closeResultSet(rs);			
			closeStatement(s);
			releaseSharedLock();
		}
	}

	/**
	 * Get count of blackboard attribute types
	 *
	 * Counts both static (in enum) and dynamic attributes types (created by
	 * modules at runtime)
	 *
	 * @return count of attribute types
	 * @throws TskCoreException exception thrown if a critical error occurs
	 * within tsk core
	 */
	public int getBlackboardAttributeTypesCount() throws TskCoreException {
		acquireSharedLock();
		Statement s = null;				
		ResultSet rs = null;
		try {
			CaseDbConnection connection = connections.getConnection();			
			s = connection.createStatement();
			rs = connection.executeQuery(s, "SELECT COUNT(*) FROM blackboard_attribute_types");
			if (rs.next()) {
				return rs.getInt(1);
			} else {
				throw new TskCoreException("Error getting count of attribute types. ");
			}
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting number of blackboard artifacts by type. " + ex.getMessage(), ex);
		} finally {
			closeResultSet(rs);			
			closeStatement(s);
			releaseSharedLock();
		}
	}

	/**
	 * Helper method to get all artifacts matching the type id name and object
	 * id
	 *
	 * @param artifactTypeID artifact type id
	 * @param artifactTypeName artifact type name
	 * @param obj_id associated object id
	 * @return list of blackboard artifacts
	 * @throws TskCoreException exception thrown if a critical error occurs
	 * within tsk core
	 */
	private ArrayList<BlackboardArtifact> getArtifactsHelper(int artifactTypeID, String artifactTypeName, long obj_id) throws TskCoreException {
		acquireSharedLock();
		ResultSet rs = null;
		try {
			CaseDbConnection connection = connections.getConnection();
			PreparedStatement statement = connection.getPreparedStatement(CaseDbConnection.PREPARED_STATEMENT.SELECT_ARTIFACTS_BY_SOURCE_AND_TYPE);	
			statement.clearParameters();			
			statement.setLong(1, obj_id);
			statement.setInt(2, artifactTypeID);
			rs = connection.executeQuery(statement);		
			ArrayList<BlackboardArtifact> artifacts = new ArrayList<BlackboardArtifact>();
			while (rs.next()) {
				artifacts.add(new BlackboardArtifact(this, rs.getLong(1), obj_id, artifactTypeID, artifactTypeName, this.getArtifactTypeDisplayName(artifactTypeID)));
			}
			return artifacts;
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting or creating a blackboard artifact. " + ex.getMessage(), ex);
		} finally {
			closeResultSet(rs);
			releaseSharedLock();
		}
	}

	/**
	 * Helper method to get count of all artifacts matching the type id name and
	 * object id
	 *
	 * @param artifactTypeID artifact type id
	 * @param obj_id associated object id
	 * @return count of matching blackboard artifacts
	 * @throws TskCoreException exception thrown if a critical error occurs
	 * within tsk core
	 */
	private long getArtifactsCountHelper(int artifactTypeID, long obj_id) throws TskCoreException {
		acquireSharedLock();
		ResultSet rs = null;
		try {
			CaseDbConnection connection = connections.getConnection();
			PreparedStatement statement = connection.getPreparedStatement(CaseDbConnection.PREPARED_STATEMENT.COUNT_ARTIFACTS_BY_SOURCE_AND_TYPE);
			statement.clearParameters();			
			statement.setLong(1, obj_id);
			statement.setInt(2, artifactTypeID);
			rs = connection.executeQuery(statement);		
			long count = 0;
			if (rs.next()) {
				count = rs.getLong(1);
			} else {
				throw new TskCoreException("Error getting blackboard artifact count, no rows returned");
			}
			return count;
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting blackboard artifact count, " + ex.getMessage(), ex);
		} finally {
			closeResultSet(rs);
			releaseSharedLock();
		}
	}

	/**
	 * helper method to get all artifacts matching the type id name
	 *
	 * @param artifactTypeID artifact type id
	 * @param artifactTypeName artifact type name
	 * @return list of blackboard artifacts
	 * @throws TskCoreException exception thrown if a critical error occurs
	 * within tsk core
	 */
	private ArrayList<BlackboardArtifact> getArtifactsHelper(int artifactTypeID, String artifactTypeName) throws TskCoreException {
		acquireSharedLock();
		ResultSet rs = null;
		try {
			CaseDbConnection connection = connections.getConnection();
			PreparedStatement statement = connection.getPreparedStatement(CaseDbConnection.PREPARED_STATEMENT.SELECT_ARTIFACTS_BY_TYPE);			
			statement.clearParameters();
			statement.setInt(1, artifactTypeID);
			rs = connection.executeQuery(statement);		
			ArrayList<BlackboardArtifact> artifacts = new ArrayList<BlackboardArtifact>();
			while (rs.next()) {
				artifacts.add(new BlackboardArtifact(this, rs.getLong(1), rs.getLong(2), artifactTypeID, artifactTypeName, this.getArtifactTypeDisplayName(artifactTypeID)));
			}
			return artifacts;
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting or creating a blackboard artifact. " + ex.getMessage(), ex);
		} finally {
			closeResultSet(rs);
			releaseSharedLock();
		}
	}

	/**
	 * Get all blackboard artifacts of a given type for the given object id
	 *
	 * @param artifactTypeName artifact type name
	 * @param obj_id object id
	 * @return list of blackboard artifacts
	 * @throws TskCoreException exception thrown if a critical error occurs
	 * within tsk core
	 */
	public ArrayList<BlackboardArtifact> getBlackboardArtifacts(String artifactTypeName, long obj_id) throws TskCoreException {
		int artifactTypeID = this.getArtifactTypeID(artifactTypeName);
		if (artifactTypeID == -1) {
			return new ArrayList<BlackboardArtifact>();
		}
		return getArtifactsHelper(artifactTypeID, artifactTypeName, obj_id);
	}

	/**
	 * Get all blackboard artifacts of a given type for the given object id
	 *
	 * @param artifactTypeID artifact type id (must exist in database)
	 * @param obj_id object id
	 * @return list of blackboard artifacts
	 * @throws TskCoreException exception thrown if a critical error occurs
	 * within tsk core
	 */
	public ArrayList<BlackboardArtifact> getBlackboardArtifacts(int artifactTypeID, long obj_id) throws TskCoreException {
		String artifactTypeName = this.getArtifactTypeString(artifactTypeID);
		return getArtifactsHelper(artifactTypeID, artifactTypeName, obj_id);
	}

	/**
	 * Get all blackboard artifacts of a given type for the given object id
	 *
	 * @param artifactType artifact type enum
	 * @param obj_id object id
	 * @return list of blackboard artifacts
	 * @throws TskCoreException exception thrown if a critical error occurs
	 * within tsk core
	 */
	public ArrayList<BlackboardArtifact> getBlackboardArtifacts(ARTIFACT_TYPE artifactType, long obj_id) throws TskCoreException {
		return getArtifactsHelper(artifactType.getTypeID(), artifactType.getLabel(), obj_id);
	}

	/**
	 * Get count of all blackboard artifacts of a given type for the given
	 * object id
	 *
	 * @param artifactTypeName artifact type name
	 * @param obj_id object id
	 * @return count of blackboard artifacts
	 * @throws TskCoreException exception thrown if a critical error occurs
	 * within tsk core
	 */
	public long getBlackboardArtifactsCount(String artifactTypeName, long obj_id) throws TskCoreException {
		int artifactTypeID = this.getArtifactTypeID(artifactTypeName);
		if (artifactTypeID == -1) {
			return 0;
		}
		return getArtifactsCountHelper(artifactTypeID, obj_id);
	}

	/**
	 * Get count of all blackboard artifacts of a given type for the given
	 * object id
	 *
	 * @param artifactTypeID artifact type id (must exist in database)
	 * @param obj_id object id
	 * @return count of blackboard artifacts
	 * @throws TskCoreException exception thrown if a critical error occurs
	 * within tsk core
	 */
	public long getBlackboardArtifactsCount(int artifactTypeID, long obj_id) throws TskCoreException {
		return getArtifactsCountHelper(artifactTypeID, obj_id);
	}

	/**
	 * Get count of all blackboard artifacts of a given type for the given
	 * object id
	 *
	 * @param artifactType artifact type enum
	 * @param obj_id object id
	 * @return count of blackboard artifacts
	 * @throws TskCoreException exception thrown if a critical error occurs
	 * within tsk core
	 */
	public long getBlackboardArtifactsCount(ARTIFACT_TYPE artifactType, long obj_id) throws TskCoreException {
		return getArtifactsCountHelper(artifactType.getTypeID(), obj_id);
	}

	/**
	 * Get all blackboard artifacts of a given type
	 *
	 * @param artifactTypeName artifact type name
	 * @return list of blackboard artifacts
	 * @throws TskCoreException exception thrown if a critical error occurs
	 * within tsk core
	 */
	public ArrayList<BlackboardArtifact> getBlackboardArtifacts(String artifactTypeName) throws TskCoreException {
		int artifactTypeID = this.getArtifactTypeID(artifactTypeName);
		if (artifactTypeID == -1) {
			return new ArrayList<BlackboardArtifact>();
		}
		return getArtifactsHelper(artifactTypeID, artifactTypeName);
	}

	/**
	 * Get all blackboard artifacts of a given type
	 *
	 * @param artifactType artifact type enum
	 * @return list of blackboard artifacts
	 * @throws TskCoreException exception thrown if a critical error occurs
	 * within tsk core
	 */
	public ArrayList<BlackboardArtifact> getBlackboardArtifacts(ARTIFACT_TYPE artifactType) throws TskCoreException {
		return getArtifactsHelper(artifactType.getTypeID(), artifactType.getLabel());
	}

	/**
	 * Get all blackboard artifacts of a given type with an attribute of a given
	 * type and String value.
	 *
	 * @param artifactType artifact type enum
	 * @param attrType attribute type enum
	 * @param value String value of attribute
	 * @return list of blackboard artifacts
	 * @throws TskCoreException exception thrown if a critical error occurs
	 * within tsk core
	 */
	public List<BlackboardArtifact> getBlackboardArtifacts(ARTIFACT_TYPE artifactType, BlackboardAttribute.ATTRIBUTE_TYPE attrType, String value) throws TskCoreException {
		acquireSharedLock();
		Statement s = null;				
		ResultSet rs = null;			
		try {
			CaseDbConnection connection = connections.getConnection();			
			s = connection.createStatement();
			rs = connection.executeQuery(s, "SELECT DISTINCT blackboard_artifacts.artifact_id, "
					+ "blackboard_artifacts.obj_id, blackboard_artifacts.artifact_type_id "
					+ "FROM blackboard_artifacts, blackboard_attributes "
					+ "WHERE blackboard_artifacts.artifact_id = blackboard_attributes.artifact_id "
					+ "AND blackboard_attributes.attribute_type_id IS " + attrType.getTypeID()
					+ " AND blackboard_artifacts.artifact_type_id = " + artifactType.getTypeID()
					+ " AND blackboard_attributes.value_text IS '" + value + "'");
			return getArtifactsHelper(rs);
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting blackboard artifacts by artifact type and attribute. " + ex.getMessage(), ex);
		} finally {
			closeResultSet(rs);			
			closeStatement(s);
			releaseSharedLock();
		}
	}

	/**
	 * Get the blackboard artifact with the given artifact id
	 *
	 * @param artifactID artifact ID
	 * @return blackboard artifact
	 * @throws TskCoreException exception thrown if a critical error occurs
	 * within tsk core
	 */
	public BlackboardArtifact getBlackboardArtifact(long artifactID) throws TskCoreException {
		acquireSharedLock();
		ResultSet rs = null;
		try {
			CaseDbConnection connection = connections.getConnection();
			PreparedStatement statement = connection.getPreparedStatement(CaseDbConnection.PREPARED_STATEMENT.SELECT_ARTIFACT_BY_ID);						
			statement.clearParameters();
			statement.setLong(1, artifactID);
			rs = connection.executeQuery(statement);		
			long obj_id = rs.getLong(1);
			int artifact_type_id = rs.getInt(2);
			return new BlackboardArtifact(this, artifactID, obj_id, artifact_type_id, 
					this.getArtifactTypeString(artifact_type_id), this.getArtifactTypeDisplayName(artifact_type_id));
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting a blackboard artifact. " + ex.getMessage(), ex);
		} finally {
			closeResultSet(rs);
			releaseSharedLock();
		}
	}

	/**
	 * Add a blackboard attribute. All information for the attribute should be
	 * in the given attribute
	 *
	 * @param attr a blackboard attribute. All necessary information should be
	 * filled in.
	 * @throws TskCoreException exception thrown if a critical error occurs
	 * within tsk core
	 */
	public void addBlackboardAttribute(BlackboardAttribute attr) throws TskCoreException {
		acquireExclusiveLock();
		try {
			CaseDbConnection connection = connections.getConnection();
			addBlackBoardAttribute(attr, connection);
		} catch (SQLException ex) {
			throw new TskCoreException("Error adding blackboard attribute: " + attr.toString(), ex);
		} finally {
			releaseExclusiveLock();
		}
	}

	/**
	 * Add a blackboard attributes in bulk. All information for the attribute
	 * should be in the given attribute
	 *
	 * @param attributes collection of blackboard attributes. All necessary
	 * information should be filled in.
	 * @throws TskCoreException exception thrown if a critical error occurs
	 * within tsk core
	 */
	public void addBlackboardAttributes(Collection<BlackboardAttribute> attributes) throws TskCoreException {
		acquireExclusiveLock();
		try {
			CaseDbConnection connection = connections.getConnection();
			connection.beginTransaction();
			for (final BlackboardAttribute attr : attributes) {
				try {
					addBlackBoardAttribute(attr, connection);
				} catch (SQLException ex) {
					logger.log(Level.WARNING, "Error adding attribute: " + attr.toString(), ex);
				}
			}
			connection.commitTransaction();
		} catch (SQLException ex) {
			throw new TskCoreException("Error committing transaction, no attributes created.", ex);
		} finally {
				releaseExclusiveLock();
		}
	}

	private void addBlackBoardAttribute(BlackboardAttribute attr, CaseDbConnection connection) throws SQLException, TskCoreException {
		PreparedStatement statement;
		switch (attr.getValueType()) {
			case STRING:
				statement = connection.getPreparedStatement(CaseDbConnection.PREPARED_STATEMENT.INSERT_STRING_ATTRIBUTE);
				statement.clearParameters();		
				statement.setString(6, escapeForBlackboard(attr.getValueString()));
				break;
			case BYTE:
				statement = connection.getPreparedStatement(CaseDbConnection.PREPARED_STATEMENT.INSERT_BYTE_ATTRIBUTE);
				statement.clearParameters();		
				statement.setBytes(6, attr.getValueBytes());
				break;
			case INTEGER:
				statement = connection.getPreparedStatement(CaseDbConnection.PREPARED_STATEMENT.INSERT_INT_ATTRIBUTE);
				statement.clearParameters();		
				statement.setInt(6, attr.getValueInt());
				break;
			case LONG:
				statement = connection.getPreparedStatement(CaseDbConnection.PREPARED_STATEMENT.INSERT_LONG_ATTRIBUTE);
				statement.clearParameters();		
				statement.setLong(6, attr.getValueLong());
				break;
			case DOUBLE:
				statement = connection.getPreparedStatement(CaseDbConnection.PREPARED_STATEMENT.INSERT_DOUBLE_ATTRIBUTE);
				statement.clearParameters();		
				statement.setDouble(6, attr.getValueDouble());
				break;
			default:
				throw new TskCoreException("Unrecognized attribute vaslue type.");					
		}
		statement.setLong(1, attr.getArtifactID());
		statement.setString(2, attr.getModuleName());
		statement.setString(3, attr.getContext());
		statement.setInt(4, attr.getAttributeTypeID());
		statement.setLong(5, attr.getValueType().getType());
		connection.executeUpdate(statement);
	}
	
	/**
	 * add an attribute type with the given name
	 *
	 * @param attrTypeString name of the new attribute
	 * @param displayName the (non-unique) display name of the attribute type
	 * @return the id of the new attribute
	 * @throws TskCoreException exception thrown if a critical error occurs
	 * within tsk core
	 */
	public int addAttrType(String attrTypeString, String displayName) throws TskCoreException {
		addAttrType(attrTypeString, displayName, attributeIDcounter);
		int retval = attributeIDcounter;
		attributeIDcounter++; // TODO: THIS IS NOT THREAD-SAFE
		return retval;
	}

	/**
	 * helper method. add an attribute type with the given name and id
	 *
	 * @param attrTypeString type name
	 * @param displayName the (non-unique) display name of the attribute type
	 * @param typeID type id
	 * @throws TskCoreException exception thrown if a critical error occurs
	 * within tsk core
	 */
	private void addAttrType(String attrTypeString, String displayName, int typeID) throws TskCoreException {
		acquireExclusiveLock();
		Statement s = null;				
		ResultSet rs = null;
		try {
			CaseDbConnection connection = connections.getConnection();			
			s = connection.createStatement();
			rs = connection.executeQuery(s, "SELECT * from blackboard_attribute_types WHERE type_name = '" + attrTypeString + "'");
			if (!rs.next()) {
				connection.executeUpdate("INSERT INTO blackboard_attribute_types (attribute_type_id, type_name, display_name) VALUES (" + typeID + ", '" + attrTypeString + "', '" + displayName + "')");
			} else {
				throw new TskCoreException("Attribute with that name already exists");
			}
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting attribute type id", ex);
		} finally {
			closeResultSet(rs);
			closeStatement(s);
			releaseExclusiveLock();
		}
	}

	/**
	 * Get the attribute type id associated with an attribute type name.
	 *
	 * @param attrTypeName An attribute type name.
	 * @return An attribute id or -1 if the attribute type does not exist.
	 * @throws TskCoreException If an error occurs accessing the case database.
	 * 
	 */
	public int getAttrTypeID(String attrTypeName) throws TskCoreException {
		acquireSharedLock();
		Statement s = null;				
		ResultSet rs = null;
		try {
			CaseDbConnection connection = connections.getConnection();			
			s = connection.createStatement();
			rs = connection.executeQuery(s, "SELECT attribute_type_id FROM blackboard_attribute_types WHERE type_name = '" + attrTypeName + "'");
			int typeId = -1;
			if (rs.next()) {
				typeId = rs.getInt(1);
			}
			return typeId;
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting attribute type id: ", ex);
		} finally {
			closeResultSet(rs);
			closeStatement(s);
			releaseSharedLock();
		}
	}

	/**
	 * Get the string associated with the given id. Will throw an error if that
	 * id does not exist
	 *
	 * @param attrTypeID attribute id
	 * @return string associated with the given id
	 * @throws TskCoreException exception thrown if a critical error occurs
	 * within tsk core
	 */
	public String getAttrTypeString(int attrTypeID) throws TskCoreException {
		acquireSharedLock();
		Statement s = null;				
		ResultSet rs = null;			
		try {
			CaseDbConnection connection = connections.getConnection();			
			s = connection.createStatement();
			rs = connection.executeQuery(s, "SELECT type_name FROM blackboard_attribute_types WHERE attribute_type_id = " + attrTypeID);
			if (rs.next()) {
				return rs.getString(1);
			} else {
				throw new TskCoreException("No type with that id");
			}
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting or creating a attribute type name", ex);
		} finally {
			closeResultSet(rs);			
			closeStatement(s);
			releaseSharedLock();
		}
	}

	/**
	 * Get the display name for the attribute with the given id. Will throw an
	 * error if that id does not exist
	 *
	 * @param attrTypeID attribute id
	 * @return string associated with the given id
	 * @throws TskCoreException exception thrown if a critical error occurs
	 * within tsk core
	 */
	public String getAttrTypeDisplayName(int attrTypeID) throws TskCoreException {
		acquireSharedLock();
		Statement s = null;				
		ResultSet rs = null;			
		try {
			CaseDbConnection connection = connections.getConnection();			
			s = connection.createStatement();
			rs = connection.executeQuery(s, "SELECT display_name FROM blackboard_attribute_types WHERE attribute_type_id = " + attrTypeID);
			if (rs.next()) {
				return rs.getString(1);
			} else {
				throw new TskCoreException("No type with that id");
			}
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting or creating a attribute type name", ex);
		} finally {
			closeResultSet(rs);			
			closeStatement(s);
			releaseSharedLock();
		}
	}

	/**
	 * Get the artifact type id associated with an artifact type name.
	 *
	 * @param artifactTypeName An artifact type name.
	 * @return An artifact id or -1 if the attribute type does not exist.
	 * @throws TskCoreException If an error occurs accessing the case database.
	 * 
	 */
	public int getArtifactTypeID(String artifactTypeName) throws TskCoreException {
		acquireSharedLock();
		Statement s = null;				
		ResultSet rs = null;
		try {
			CaseDbConnection connection = connections.getConnection();			
			s = connection.createStatement();
			rs = connection.executeQuery(s, "SELECT artifact_type_id FROM blackboard_artifact_types WHERE type_name = '" + artifactTypeName + "'");
			int typeId = -1;
			if (rs.next()) {
				typeId = rs.getInt(1);
			}
			return typeId;
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting artifact type id: " + ex.getMessage(), ex);
		} finally {
			closeResultSet(rs);
			closeStatement(s);
			releaseSharedLock();
		}
	}
	
	/**
	 * Get artifact type name for the given string. Will throw an error if that
	 * artifact doesn't exist. Use addArtifactType(...) to create a new one.
	 *
	 * @param artifactTypeID id for an artifact type
	 * @return name of that artifact type
	 * @throws TskCoreException exception thrown if a critical error occurs
	 * within tsk core
	 */
	String getArtifactTypeString(int artifactTypeID) throws TskCoreException {
		acquireSharedLock();
		Statement s = null;				
		ResultSet rs = null;			
		try {
			CaseDbConnection connection = connections.getConnection();			
			s = connection.createStatement();
			rs = connection.executeQuery(s, "SELECT type_name FROM blackboard_artifact_types WHERE artifact_type_id = " + artifactTypeID);
			if (rs.next()) {
				return rs.getString(1);
			} else {
				throw new TskCoreException("Error: no artifact with that name in database");
			}
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting artifact type id.", ex);
		} finally {
			closeResultSet(rs);			
			closeStatement(s);
			releaseSharedLock();
		}
	}

	/**
	 * Get artifact type display name for the given string. Will throw an error
	 * if that artifact doesn't exist. Use addArtifactType(...) to create a new
	 * one.
	 *
	 * @param artifactTypeID id for an artifact type
	 * @return display name of that artifact type
	 * @throws TskCoreException exception thrown if a critical error occurs
	 * within tsk core
	 */
	String getArtifactTypeDisplayName(int artifactTypeID) throws TskCoreException {
		acquireSharedLock();
		Statement s = null;				
		ResultSet rs = null;						
		try {
			CaseDbConnection connection = connections.getConnection();			
			s = connection.createStatement();
			rs = connection.executeQuery(s, "SELECT display_name FROM blackboard_artifact_types WHERE artifact_type_id = " + artifactTypeID);
			if (rs.next()) {
				return rs.getString(1);
			} else {
				throw new TskCoreException("Error: no artifact with that name in database");
			}
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting artifact type id", ex);
		} finally {
			closeResultSet(rs);			
			closeStatement(s);
			releaseSharedLock();
		}
	}

	/**
	 * Add an artifact type with the given name. Will return an id that can be
	 * used to look that artifact type up.
	 *
	 * @param artifactTypeName System (unique) name of artifact
	 * @param displayName Display (non-unique) name of artifact
	 * @return ID of artifact added
	 * @throws TskCoreException exception thrown if a critical error occurs
	 * within tsk core
	 */
	public int addArtifactType(String artifactTypeName, String displayName) throws TskCoreException {
		addArtifactType(artifactTypeName, displayName, artifactIDcounter);
		int retval = artifactIDcounter;
		artifactIDcounter++; // TODO: THIS IS NOT THREAD-SAFE
		return retval;
	}

	/**
	 * helper method. add an artifact with the given type and id
	 *
	 * @param artifactTypeName type name
	 * @param displayName Display (non-unique) name of artifact
	 * @param typeID type id
	 * @throws TskCoreException exception thrown if a critical error occurs
	 * within tsk core
	 */
	private void addArtifactType(String artifactTypeName, String displayName, int typeID) throws TskCoreException {
		acquireExclusiveLock();
		Statement s = null;				
		ResultSet rs = null;
		try {
			CaseDbConnection connection = connections.getConnection();			
			s = connection.createStatement();
			rs = connection.executeQuery(s, "SELECT * FROM blackboard_artifact_types WHERE type_name = '" + artifactTypeName + "'");
			if (!rs.next()) {
				connection.executeUpdate("INSERT INTO blackboard_artifact_types (artifact_type_id, type_name, display_name) VALUES (" + typeID + " , '" + artifactTypeName + "', '" + displayName + "')");
			} else {
				throw new TskCoreException("Artifact with that name already exists");
			}
		} catch (SQLException ex) {
			throw new TskCoreException("Error adding artifact type", ex);
		} finally {
			closeResultSet(rs);
			closeStatement(s);
			releaseExclusiveLock();
		}
	}

	public ArrayList<BlackboardAttribute> getBlackboardAttributes(final BlackboardArtifact artifact) throws TskCoreException {
		acquireSharedLock();
		ResultSet rs = null;
		try {
			CaseDbConnection connection = connections.getConnection();
			PreparedStatement statement = connection.getPreparedStatement(CaseDbConnection.PREPARED_STATEMENT.SELECT_ATTRIBUTES_OF_ARTIFACT);
			statement.clearParameters();
			statement.setLong(1, artifact.getArtifactID());
			rs = connection.executeQuery(statement);		
			ArrayList<BlackboardAttribute> attributes = new ArrayList<BlackboardAttribute>();
			while (rs.next()) {
				final BlackboardAttribute attr = new BlackboardAttribute(
						rs.getLong(1),
						rs.getInt(4),
						rs.getString(2),
						rs.getString(3),
						BlackboardAttribute.TSK_BLACKBOARD_ATTRIBUTE_VALUE_TYPE.fromType(rs.getInt(5)),
						rs.getInt(8),
						rs.getLong(9),
						rs.getDouble(10),
						rs.getString(7),
						rs.getBytes(6), this);
				attributes.add(attr);
			}
			return attributes;
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting attributes for artifact: " + artifact.getArtifactID(), ex);
		} finally {
			closeResultSet(rs);
			releaseSharedLock();
		}
	}

	/**
	 * Get all attributes that match a where clause. The clause should begin
	 * with "WHERE" or "JOIN". To use this method you must know the database
	 * tables
	 *
	 * @param whereClause a sqlite where clause
	 * @return a list of matching attributes
	 * @throws TskCoreException exception thrown if a critical error occurs
	 * within tsk core
	 */
	public ArrayList<BlackboardAttribute> getMatchingAttributes(String whereClause) throws TskCoreException {
		acquireSharedLock();
		Statement s = null;				
		ResultSet rs = null;			
		try {
			CaseDbConnection connection = connections.getConnection();			
			s = connection.createStatement();
			rs = connection.executeQuery(s, "Select artifact_id, source, context, attribute_type_id, value_type, "
					+ "value_byte, value_text, value_int32, value_int64, value_double FROM blackboard_attributes " + whereClause);
			ArrayList<BlackboardAttribute> matches = new ArrayList<BlackboardAttribute>();
			while (rs.next()) {
				BlackboardAttribute attr = new BlackboardAttribute(rs.getLong("artifact_id"), rs.getInt("attribute_type_id"), rs.getString("source"), rs.getString("context"),
						BlackboardAttribute.TSK_BLACKBOARD_ATTRIBUTE_VALUE_TYPE.fromType(rs.getInt("value_type")), rs.getInt("value_int32"), rs.getLong("value_int64"), rs.getDouble("value_double"),
						rs.getString("value_text"), rs.getBytes("value_byte"), this);
				matches.add(attr);
			}
			return matches;
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting attributes. using this where clause: " + whereClause, ex);
		} finally {
			closeResultSet(rs);			
			closeStatement(s);
			releaseSharedLock();
		}
	}

	/**
	 * Get all artifacts that match a where clause. The clause should begin with
	 * "WHERE" or "JOIN". To use this method you must know the database tables
	 *
	 * @param whereClause a sqlite where clause
	 * @return a list of matching artifacts
	 * @throws TskCoreException exception thrown if a critical error occurs
	 * within tsk core
	 */
	public ArrayList<BlackboardArtifact> getMatchingArtifacts(String whereClause) throws TskCoreException {
		acquireSharedLock();
		ResultSet rs = null;			
		Statement s = null;				
		try {
			CaseDbConnection connection = connections.getConnection();			
			s = connection.createStatement();
			rs = connection.executeQuery(s, "SELECT artifact_id, obj_id, artifact_type_id FROM blackboard_artifacts " + whereClause);
			ArrayList<BlackboardArtifact> matches = new ArrayList<BlackboardArtifact>();
			while (rs.next()) {
				BlackboardArtifact artifact = new BlackboardArtifact(this, rs.getLong(1), rs.getLong(2), rs.getInt(3), this.getArtifactTypeString(rs.getInt(3)), this.getArtifactTypeDisplayName(rs.getInt(3)));
				matches.add(artifact);
			}
			return matches;
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting attributes. using this where clause: " + whereClause, ex);
		} finally {
			closeResultSet(rs);			
			closeStatement(s);
			releaseSharedLock();
		}
	}

	/**
	 * Add a new blackboard artifact with the given type. If that artifact type
	 * does not exist an error will be thrown. The artifact type name can be
	 * looked up in the returned blackboard artifact.
	 *
	 * @param artifactTypeID the type the given artifact should have
	 * @param obj_id the content object id associated with this artifact
	 * @return a new blackboard artifact
	 * @throws TskCoreException exception thrown if a critical error occurs
	 * within tsk core
	 */
	public BlackboardArtifact newBlackboardArtifact(int artifactTypeID, long obj_id) throws TskCoreException {
		acquireExclusiveLock();
		ResultSet rs = null;
		try {
			String artifactTypeName = getArtifactTypeString(artifactTypeID);
			String artifactDisplayName = getArtifactTypeDisplayName(artifactTypeID);

			CaseDbConnection connection = connections.getConnection();
			PreparedStatement statement = connection.getPreparedStatement(CaseDbConnection.PREPARED_STATEMENT.INSERT_ARTIFACT);	
			statement.clearParameters();
			statement.setLong(1, obj_id);
			statement.setInt(2, artifactTypeID);
			connection.executeUpdate(statement);

			statement = connection.getPreparedStatement(CaseDbConnection.PREPARED_STATEMENT.SELECT_MAX_ARTIFACT_ID_BY_SOURCE_AND_TYPE);			
			statement.clearParameters();
			statement.setLong(1, obj_id);
			statement.setInt(2, artifactTypeID);
			rs = connection.executeQuery(statement);		
			long artifactID = rs.getLong(1);

			return new BlackboardArtifact(this, artifactID, obj_id, artifactTypeID,
					artifactTypeName, artifactDisplayName);

		} catch (SQLException ex) {
			throw new TskCoreException("Error creating a blackboard artifact. " + ex.getMessage(), ex);
		} finally {
			closeResultSet(rs);
			releaseExclusiveLock();
		}
	}

	/**
	 * Add a new blackboard artifact with the given type.
	 *
	 * @param artifactType the type the given artifact should have
	 * @param obj_id the content object id associated with this artifact
	 * @return a new blackboard artifact
	 * @throws TskCoreException exception thrown if a critical error occurs
	 * within tsk core
	 */
	public BlackboardArtifact newBlackboardArtifact(ARTIFACT_TYPE artifactType, long obj_id) throws TskCoreException {
		acquireExclusiveLock();
		ResultSet rs = null;
		try {
			final int type = artifactType.getTypeID();

			CaseDbConnection connection = connections.getConnection();
			PreparedStatement statement = connection.getPreparedStatement(CaseDbConnection.PREPARED_STATEMENT.INSERT_ARTIFACT);			
			statement.clearParameters();
			statement.setLong(1, obj_id);
			statement.setInt(2, type);
			connection.executeUpdate(statement);

			statement = connection.getPreparedStatement(CaseDbConnection.PREPARED_STATEMENT.SELECT_MAX_ARTIFACT_ID_BY_SOURCE_AND_TYPE);			
			statement.clearParameters();
			statement.setLong(1, obj_id);
			statement.setInt(2, type);
			rs = connection.executeQuery(statement);		
			long artifactID = -1;
			if (rs.next()) {
				artifactID = rs.getLong(1);
			}

			return new BlackboardArtifact(this, artifactID, obj_id, type,
					artifactType.getLabel(), artifactType.getDisplayName());

		} catch (SQLException ex) {
			throw new TskCoreException("Error getting or creating a blackboard artifact. " + ex.getMessage(), ex);
		} finally {
			closeResultSet(rs);
			releaseExclusiveLock();
		}
	}

	/**
	 * Add one of the built in artifact types
	 *
	 * @param type type enum
	 * @throws TskException
	 * @throws TskCoreException exception thrown if a critical error occurs
	 * within tsk core
	 */
	private void addBuiltInArtifactType(ARTIFACT_TYPE type) throws TskCoreException {
		addArtifactType(type.getLabel(), type.getDisplayName(), type.getTypeID());
	}

	/**
	 * Add one of the built in attribute types
	 *
	 * @param type type enum
	 * @throws TskCoreException exception thrown if a critical error occurs
	 * within tsk core
	 */
	private void addBuiltInAttrType(ATTRIBUTE_TYPE type) throws TskCoreException {
		addAttrType(type.getLabel(), type.getDisplayName(), type.getTypeID());
	}

	/**
	 * Checks if the content object has children. Note: this is generally more
	 * efficient then preloading all children and checking if the set is empty,
	 * and facilities lazy loading.
	 *
	 * @param content content object to check for children
	 * @return true if has children, false otherwise
	 * @throws TskCoreException exception thrown if a critical error occurs
	 * within tsk core
	 */
	boolean getContentHasChildren(Content content) throws TskCoreException {
		acquireSharedLock();
		ResultSet rs = null;
		try {
			CaseDbConnection connection = connections.getConnection();
			PreparedStatement statement = connection.getPreparedStatement(CaseDbConnection.PREPARED_STATEMENT.COUNT_CHILD_OBJECTS_BY_PARENT);						
			statement.clearParameters();
			statement.setLong(1, content.getId());
			rs = connection.executeQuery(statement);		
			boolean hasChildren = false;
			if (rs.next()) {
				hasChildren = rs.getInt(1) > 0; // RJCTODO: This is wrong
			}
			return hasChildren;
		} catch (SQLException e) {
			throw new TskCoreException("Error checking for children of parent: " + content, e);
		} finally {
			closeResultSet(rs);
			releaseSharedLock();
		}
	}

	/**
	 * Counts if the content object children. Note: this is generally more
	 * efficient then preloading all children and counting, and facilities lazy
	 * loading.
	 *
	 * @param content content object to check for children count
	 * @return children count
	 * @throws TskCoreException exception thrown if a critical error occurs
	 * within tsk core
	 */
	int getContentChildrenCount(Content content) throws TskCoreException {
		acquireSharedLock();
		ResultSet rs = null;
		try {
			CaseDbConnection connection = connections.getConnection();
			PreparedStatement statement = connection.getPreparedStatement(CaseDbConnection.PREPARED_STATEMENT.COUNT_CHILD_OBJECTS_BY_PARENT);						
			statement.clearParameters();
			statement.setLong(1, content.getId());
			rs = connection.executeQuery(statement);		
			int countChildren = -1;
			if (rs.next()) {
				countChildren = rs.getInt(1);
			}
			return countChildren;
		} catch (SQLException e) {
			throw new TskCoreException("Error checking for children of parent: " + content, e);
		} finally {
			closeResultSet(rs);
			releaseSharedLock();
		}
	}

	/**
	 * Returns the list of AbstractFile Children of a given type for a given AbstractFileParent
	 *
	 * @param parent the content parent to get abstract file children for
	 * @param type children type to look for, defined in TSK_DB_FILES_TYPE_ENUM
	 * @throws TskCoreException exception thrown if a critical error occurs
	 * within tsk core
	 */
	List<Content> getAbstractFileChildren(Content parent, TSK_DB_FILES_TYPE_ENUM type) throws TskCoreException {
		acquireSharedLock();
		ResultSet rs = null;
		try {
			CaseDbConnection connection = connections.getConnection();
			PreparedStatement statement = connection.getPreparedStatement(CaseDbConnection.PREPARED_STATEMENT.SELECT_FILES_BY_PARENT_AND_TYPE);						
			statement.clearParameters();
			long parentId = parent.getId();
			statement.setLong(1, parentId);
			statement.setShort(2, type.getFileType());			
			rs = connection.executeQuery(statement);		
			return rsHelper.fileChildren(rs, parentId);
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting AbstractFile children for Content.", ex);
		} finally {
			closeResultSet(rs);
			releaseSharedLock();
		}
	}
	
	/**
	 * Returns the list of all AbstractFile Children for a given AbstractFileParent
	 *
	 * @param parent the content parent to get abstract file children for
	 * @param type children type to look for, defined in TSK_DB_FILES_TYPE_ENUM
	 * @throws TskCoreException exception thrown if a critical error occurs
	 * within tsk core
	 */
	List<Content> getAbstractFileChildren(Content parent) throws TskCoreException {
		acquireSharedLock();
		ResultSet rs = null;
		try {
			CaseDbConnection connection = connections.getConnection();
			PreparedStatement statement = connection.getPreparedStatement(CaseDbConnection.PREPARED_STATEMENT.SELECT_FILES_BY_PARENT);						
			statement.clearParameters();
			long parentId = parent.getId();
			statement.setLong(1, parentId);			
			rs = connection.executeQuery(statement);		
			return rsHelper.fileChildren(rs, parentId);
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting AbstractFile children for Content.", ex);
		} finally {
			closeResultSet(rs);
			releaseSharedLock();
		}
	}

	/**
	 * Get list of IDs for abstract files of a given type that are children of a given content.
	 * @param parent Object to find children for
	 * @param type Type of children to find  IDs for
	 * @return
	 * @throws TskCoreException 
	 */
	List<Long> getAbstractFileChildrenIds(Content parent, TSK_DB_FILES_TYPE_ENUM type) throws TskCoreException {
		acquireSharedLock();
		ResultSet rs = null;		
		try {
			CaseDbConnection connection = connections.getConnection();
			PreparedStatement statement = connection.getPreparedStatement(CaseDbConnection.PREPARED_STATEMENT.SELECT_FILE_IDS_BY_PARENT_AND_TYPE);						
			statement.clearParameters();
			statement.setLong(1, parent.getId());
			statement.setShort(2, type.getFileType());
			rs = connection.executeQuery(statement);		
			List<Long> children = new ArrayList<Long>();
			while (rs.next()) {
				children.add(rs.getLong(1));
			}
			return children;
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting AbstractFile children for Content.", ex);
		} finally {
			closeResultSet(rs);
			releaseSharedLock();
		}
	}
	
	/**
	 * Get list of IDs for abstract files that are children of a given content.
	 * @param parent Object to find children for
	 * @return
	 * @throws TskCoreException 
	 */
	List<Long> getAbstractFileChildrenIds(Content parent) throws TskCoreException {
		acquireSharedLock();
		ResultSet rs = null;
		try {
			CaseDbConnection connection = connections.getConnection();
			PreparedStatement statement = connection.getPreparedStatement(CaseDbConnection.PREPARED_STATEMENT.SELECT_FILE_IDS_BY_PARENT);						
			statement.clearParameters();
			statement.setLong(1, parent.getId());
			rs = connection.executeQuery(statement);		
			List<Long> children = new ArrayList<Long>();
			while (rs.next()) {
				children.add(rs.getLong(1));
			}
			return children;
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting AbstractFile children for Content.", ex);
		} finally {
			closeResultSet(rs);
			releaseSharedLock();
		}
	}

	/**
	 * Stores a pair of object ID and its type
	 */
	static class ObjectInfo {

		long id;
		TskData.ObjectType type;

		ObjectInfo(long id, ObjectType type) {
			this.id = id;
			this.type = type;
		}
	}

	/**
	 * Get info about children of a given Content from the database. TODO: the
	 * results of this method are volumes, file systems, and fs files.
	 *
	 * @param c Parent object to run query against
	 * @throws TskCoreException exception thrown if a critical error occurs
	 * within tsk core
	 */
	Collection<ObjectInfo> getChildrenInfo(Content c) throws TskCoreException {
		acquireSharedLock();
		Statement s = null;				
		ResultSet rs = null;			
		try {
			CaseDbConnection connection = connections.getConnection();			
			s = connection.createStatement();
			rs = connection.executeQuery(s, "SELECT tsk_objects.obj_id, tsk_objects.type "
					+ "FROM tsk_objects left join tsk_files "
					+ "ON tsk_objects.obj_id=tsk_files.obj_id "
					+ "WHERE tsk_objects.par_obj_id = " + c.getId());
			Collection<ObjectInfo> infos = new ArrayList<ObjectInfo>();
			while (rs.next()) {
				infos.add(new ObjectInfo(rs.getLong("obj_id"), ObjectType.valueOf(rs.getShort("type"))));
			}
			return infos;
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting Children Info for Content", ex);
		} finally {
			closeResultSet(rs);			
			closeStatement(s);
			releaseSharedLock();
		}
	}

	/**
	 * Get parent info for the parent of the content object
	 *
	 * @param c content object to get parent info for
	 * @return the parent object info with the parent object type and id
	 * @throws TskCoreException exception thrown if a critical error occurs
	 * within tsk core
	 */
	ObjectInfo getParentInfo(Content c) throws TskCoreException {
		acquireSharedLock();
		Statement s = null;				
		ResultSet rs = null;			
		try {
			CaseDbConnection connection = connections.getConnection();			
			s = connection.createStatement();
			rs = connection.executeQuery(s, "SELECT parent.obj_id, parent.type "
					+ "FROM tsk_objects AS parent INNER JOIN tsk_objects AS child "
					+ "ON child.par_obj_id = parent.obj_id "
					+ "WHERE child.obj_id = " + c.getId());
			if (rs.next()) {
				return new ObjectInfo(rs.getLong(1), ObjectType.valueOf(rs.getShort(2)));
			} else {
				throw new TskCoreException("Given content (id: " + c.getId() + ") has no parent.");
			}
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting Parent Info for Content.", ex);
		} finally {
			closeResultSet(rs);			
			closeStatement(s);
			releaseSharedLock();
		}
	}

	/**
	 * Get parent info for the parent of the content object id
	 *
	 * @param id content object id to get parent info for
	 * @return the parent object info with the parent object type and id
	 * @throws TskCoreException exception thrown if a critical error occurs
	 * within tsk core
	 */
	ObjectInfo getParentInfo(long contentId) throws TskCoreException {
		acquireSharedLock();
		Statement s = null;				
		ResultSet rs = null;			
		try {
			CaseDbConnection connection = connections.getConnection();			
			s = connection.createStatement();
			rs = connection.executeQuery(s, "SELECT parent.obj_id, parent.type "
					+ "FROM tsk_objects AS parent INNER JOIN tsk_objects AS child "
					+ "ON child.par_obj_id = parent.obj_id "
					+ "WHERE child.obj_id = " + contentId);
			if (rs.next()) {
				return new ObjectInfo(rs.getLong(1), ObjectType.valueOf(rs.getShort(2)));
			} else {
				throw new TskCoreException("Given content (id: " + contentId + ") has no parent.");
			}
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting Parent Info for Content: " + contentId, ex);
		} finally {
			closeResultSet(rs);			
			closeStatement(s);
			releaseSharedLock();
		}
	}

	/**
	 * Gets parent directory for FsContent object
	 *
	 * @param fsc FsContent to get parent dir for
	 * @return the parent Directory
	 * @throws TskCoreException thrown if critical error occurred within tsk
	 * core
	 */
	Directory getParentDirectory(FsContent fsc) throws TskCoreException {
		if (fsc.isRoot()) {
			throw new TskCoreException("Given FsContent (id: " + fsc.getId() + ") is a root object (can't have parent directory).");
		} else {
			ObjectInfo parentInfo = getParentInfo(fsc);
			Directory parent = null;
			if (parentInfo.type == ObjectType.ABSTRACTFILE) {
				parent = getDirectoryById(parentInfo.id, fsc.getFileSystem());
			} else {
				throw new TskCoreException("Parent of FsContent (id: " + fsc.getId() + ") has wrong type to be directory: " + parentInfo.type);
			}
			return parent;
		}
	}

	/**
	 * Get content object by content id
	 *
	 * @param id to get content object for
	 * @return instance of a Content object (one of its subclasses), or null if
	 * not found.
	 * @throws TskCoreException thrown if critical error occurred within tsk
	 * core
	 */
	public Content getContentById(long id) throws TskCoreException {
		acquireSharedLock();
		Statement s = null;				
		ResultSet rs = null;			
		try {
			CaseDbConnection connection = connections.getConnection();			
			s = connection.createStatement();
			rs = connection.executeQuery(s, "SELECT * FROM tsk_objects WHERE obj_id = " + id + " LIMIT  1");
			if (!rs.next()) {
				return null;
			}

			AbstractContent content = null;
			long parentId = rs.getLong("par_obj_id");
			final TskData.ObjectType type = TskData.ObjectType.valueOf(rs.getShort("type"));
			switch (type) {
				case IMG:
					content = getImageById(id);
					break;
				case VS:
					content = getVolumeSystemById(id, parentId);
					break;
				case VOL:
					content = getVolumeById(id, parentId);
					break;
				case FS:
					content = getFileSystemById(id, parentId);
					break;
				case ABSTRACTFILE:
					content = getAbstractFileById(id);
					break;
				default:
					throw new TskCoreException("Could not obtain Content object with ID: " + id);
			}
			return content;
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting Content by ID.", ex);
		} finally {
			closeResultSet(rs);			
			closeStatement(s);
			releaseSharedLock();
		}
	}

	/**
	 * Get a path of a file in tsk_files_path table or null if there is none
	 *
	 * @param id id of the file to get path for
	 * @return file path or null
	 */
	String getFilePath(long id) {
		acquireSharedLock();
		ResultSet rs = null;
		String filePath = null;
		try {
			CaseDbConnection connection = connections.getConnection();
			PreparedStatement statement = connection.getPreparedStatement(CaseDbConnection.PREPARED_STATEMENT.SELECT_LOCAL_PATH_FOR_FILE);			
			statement.clearParameters();
			statement.setLong(1, id);
			rs = connection.executeQuery(statement);		
			if (rs.next()) {
				filePath = rs.getString(1);
			}
		} catch (SQLException ex) {
			logger.log(Level.SEVERE, "Error getting file path for file: " + id, ex);
		} finally {
			closeResultSet(rs);
			releaseSharedLock();
		}
		return filePath;
	}

	/**
	 * Get a parent_path of a file in tsk_files table or null if there is none
	 *
	 * @param id id of the file to get path for
	 * @return file path or null
	 */
	String getFileParentPath(long id) {
		acquireSharedLock();
		ResultSet rs = null;
		String parentPath = null;
		try {
			CaseDbConnection connection = connections.getConnection();
			PreparedStatement statement = connection.getPreparedStatement(CaseDbConnection.PREPARED_STATEMENT.SELECT_PATH_FOR_FILE);			
			statement.clearParameters();
			statement.setLong(1, id);
			rs = connection.executeQuery(statement);		
			if (rs.next()) {
				parentPath = rs.getString(1);
			}
		} catch (SQLException ex) {
			logger.log(Level.SEVERE, "Error getting file parent_path for file: " + id, ex);
		} finally {
			closeResultSet(rs);
			releaseSharedLock();
		}
		return parentPath;
	}

	/**
	 * Get a name of a file in tsk_files table or null if there is none
	 *
	 * @param id id of the file to get name for
	 * @return file name or null
	 */
	String getFileName(long id) {
		acquireSharedLock();
		ResultSet rs = null;
		String fileName = null;
		try {
			CaseDbConnection connection = connections.getConnection();
			PreparedStatement statement = connection.getPreparedStatement(CaseDbConnection.PREPARED_STATEMENT.SELECT_FILE_NAME);			
			statement.clearParameters();
			statement.setLong(1, id);
			rs = connection.executeQuery(statement);		
			if (rs.next()) {
				fileName = rs.getString(1);
			}
		} catch (SQLException ex) {
			logger.log(Level.SEVERE, "Error getting file parent_path for file: " + id, ex);
		} finally {
			closeResultSet(rs);
			releaseSharedLock();
		}
		return fileName;
	}

	/**
	 * Get a derived method for a file, or null if none
	 *
	 * @param id id of the derived file
	 * @return derived method or null if not present
	 * @throws TskCoreException exception throws if core error occurred and
	 * method could not be queried
	 */
	DerivedFile.DerivedMethod getDerivedMethod(long id) throws TskCoreException {
		acquireSharedLock();
		ResultSet rs1 = null;
		ResultSet rs2 = null;
		DerivedFile.DerivedMethod method = null;
		try {
			CaseDbConnection connection = connections.getConnection();
			PreparedStatement statement = connection.getPreparedStatement(CaseDbConnection.PREPARED_STATEMENT.SELECT_DERIVED_FILE);	
			statement.clearParameters();
			statement.setLong(1, id);
			rs1 = connection.executeQuery(statement);		
			if (rs1.next()) {
				int method_id = rs1.getInt(1);
				String rederive = rs1.getString(1);
				method = new DerivedFile.DerivedMethod(method_id, rederive);
				statement = connection.getPreparedStatement(CaseDbConnection.PREPARED_STATEMENT.SELECT_FILE_DERIVATION_METHOD);			
				statement.clearParameters();
				statement.setInt(1, method_id);
				rs2 = connection.executeQuery(statement);		
				if (rs2.next()) {
					method.setToolName(rs2.getString(1));
					method.setToolVersion(rs2.getString(2));
					method.setOther(rs2.getString(3));
				}
			}
		} catch (SQLException e) {
			logger.log(Level.SEVERE, "Error getting derived method for file: " + id, e);
		} finally {
			closeResultSet(rs2);
			closeResultSet(rs1);
			releaseSharedLock();
		}
		return method;
	}

	/**
	 * Get abstract file object from tsk_files table by its id
	 *
	 * @param id id of the file object in tsk_files table
	 * @return AbstractFile object populated, or null if not found.
	 * @throws TskCoreException thrown if critical error occurred within tsk
	 * core and file could not be queried
	 */
	public AbstractFile getAbstractFileById(long id) throws TskCoreException {
		acquireSharedLock();
		ResultSet rs = null;
		try {
			CaseDbConnection connection = connections.getConnection();
			PreparedStatement statement = connection.getPreparedStatement(CaseDbConnection.PREPARED_STATEMENT.SELECT_FILE_BY_ID);			
			statement.clearParameters();
			statement.setLong(1, id);
			rs = connection.executeQuery(statement);		
			List<AbstractFile> results;
			if ((results = resultSetToAbstractFiles(rs)).size() > 0) {
				return results.get(0);
			} else {
				return null;
			}
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting file by ID.", ex);
		} finally {
			closeResultSet(rs);
			releaseSharedLock();
		}
	}

	/**
	 * Get the object ID of the file system that a file is located in.
	 * 
	 * Note: for
	 * FsContent files, this is the real fs for other non-fs AbstractFile files,
	 * this field is used internally for data source id (the root content obj)
	 *
	 * @param fileId object id of the file to get fs column id for
	 * @return fs_id or -1 if not present
	 */
	private long getFileSystemId(long fileId) {
		acquireSharedLock();
		ResultSet rs = null;
		long ret = -1;
		try {
			CaseDbConnection connection = connections.getConnection();
			PreparedStatement statement = connection.getPreparedStatement(CaseDbConnection.PREPARED_STATEMENT.SELECT_FILE_SYSTEM_BY_OBJECT);			
			statement.clearParameters();
			statement.setLong(1, fileId);
			rs = connection.executeQuery(statement);		
			if (rs.next()) {
				ret = rs.getLong(1);
				if (ret == 0) {
					ret = -1;
				}
			}
		} catch (SQLException e) {
			logger.log(Level.SEVERE, "Error checking file system id of a file", e);
		} finally {
			closeResultSet(rs);
			releaseSharedLock();
		}
		return ret;
	}

	/**
	 * Gets the root-level data source object id (such as Image or
	 * VirtualDirectory representing filesets) for the file
	 *
	 * @param file file to get the root-level object id for
	 * @return the root content object id in the hierarchy, or -1 if not found
	 * (such as when invalid file object passed in)
	 * @throws TskCoreException thrown if check failed due to a critical tsk
	 * error
	 */
	public long getFileDataSource(AbstractFile file) throws TskCoreException {
		final Image image = file.getImage();
		if (image != null) {
			//case for image data source
			return image.getId();
		} else {
			//otherwise, get the root non-image data source id
			//note, we are currently using fs_id internally to store data source id for such files

			return getFileSystemId(file.getId());
		}
	}

	/**
	 * Checks if the file is a (sub)child of the data source (parentless Content
	 * object such as Image or VirtualDirectory representing filesets)
	 *
	 * @param dataSource dataSource to check
	 * @param fileId id of file to check
	 * @return true if the file is in the dataSource hierarchy
	 * @throws TskCoreException thrown if check failed
	 */
	public boolean isFileFromSource(Content dataSource, long fileId) throws TskCoreException {
		if (dataSource.getParent() != null) {
			final String msg = "Error, data source should be parent-less (images, file-sets), got: " + dataSource;
			logger.log(Level.SEVERE, msg);
			throw new IllegalArgumentException(msg);
		}

		//get fs_id for file id
		long fsId = getFileSystemId(fileId);
		if (fsId == -1) {
			return false;
		}

		//if image, check if one of fs in data source

		if (dataSource instanceof Image) {
			Collection<FileSystem> fss = getFileSystems((Image) dataSource);
			for (FileSystem fs : fss) {
				if (fs.getId() == fsId) {
					return true;
				}
			}
			return false;

		} //if VirtualDirectory, check if dataSource id is the fs_id
		else if (dataSource instanceof VirtualDirectory) {
			//fs_obj_id is not a real fs in this case
			//we are currently using this field internally to get to data source of non-fs files quicker
			//this will be fixed in 2.5 schema
			return dataSource.getId() == fsId;

		} else {
			final String msg = "Error, data source should be Image or VirtualDirectory, got: " + dataSource;
			logger.log(Level.SEVERE, msg);
			throw new IllegalArgumentException(msg);
		}
	}

	/**
	 * @param dataSource the dataSource (Image, parent-less VirtualDirectory) to
	 * search for the given file name
	 * @param fileName Pattern of the name of the file or directory to match
 (case insensitive, used in LIKE SQL s).
	 * @return a list of AbstractFile for files/directories whose name matches
	 * the given fileName
	 * @throws TskCoreException thrown if check failed
	 */
	public List<AbstractFile> findFiles(Content dataSource, String fileName) throws TskCoreException {
		if (dataSource.getParent() != null) {
			final String msg = "Error, data source should be parent-less (images, file-sets), got: " + dataSource;
			logger.log(Level.SEVERE, msg);
			throw new IllegalArgumentException(msg);
		}

		// set the file name in the prepared statement
		acquireSharedLock();
		List<AbstractFile> files = new ArrayList<AbstractFile>();
		ResultSet rs = null;
		try {
			CaseDbConnection connection = connections.getConnection();
			PreparedStatement statement = connection.getPreparedStatement(CaseDbConnection.PREPARED_STATEMENT.SELECT_FILES_BY_FILE_SYSTEM_AND_NAME);						
			statement.clearParameters();
			if (dataSource instanceof Image) {
				for (FileSystem fileSystem : getFileSystems((Image) dataSource)) {
					statement.setString(1, fileName.toLowerCase());
					statement.setLong(2, fileSystem.getId());
					rs = connection.executeQuery(statement);		
					files.addAll(resultSetToAbstractFiles(rs));
				}
			} else if (dataSource instanceof VirtualDirectory) {
				//fs_obj_id is special for non-fs files (denotes data source)
				statement.setString(1, fileName.toLowerCase());
				statement.setLong(2, dataSource.getId());
				rs = connection.executeQuery(statement);		
				files = resultSetToAbstractFiles(rs);
			} else {
				final String msg = "Error, data source should be Image or VirtualDirectory, got: " + dataSource;
				logger.log(Level.SEVERE, msg);
				throw new IllegalArgumentException(msg);
			}
		} catch (SQLException e) {
			throw new TskCoreException("Error finding files in the data source by name, ", e);
		} finally {
			closeResultSet(rs);
			releaseSharedLock();
		}
		return files;
	}

	/**
	 * @param dataSource the dataSource (Image, parent-less VirtualDirectory) to
	 * search for the given file name
	 * @param fileName Pattern of the name of the file or directory to match
     * (case insensitive, used in LIKE SQL s).
	 * @param dirName Pattern of the name of a parent directory of fileName
     * (case insensitive, used in LIKE SQL s)
	 * @return a list of AbstractFile for files/directories whose name matches
	 * fileName and whose parent directory contains dirName.
	 */
	public List<AbstractFile> findFiles(Content dataSource, String fileName, String dirName) throws TskCoreException {
		if (dataSource.getParent() != null) {
			final String msg = "Error, data source should be parent-less (images, file-sets), got: " + dataSource;
			logger.log(Level.SEVERE, msg);
			throw new IllegalArgumentException(msg);
		}

		ResultSet rs = null;
		List<AbstractFile> files = new ArrayList<AbstractFile>();
		acquireSharedLock();
		try {
			CaseDbConnection connection = connections.getConnection();
			PreparedStatement statement = connection.getPreparedStatement(CaseDbConnection.PREPARED_STATEMENT.SELECT_FILES_BY_FILE_SYSTEM_AND_PATH);			
			statement.clearParameters();
			if (dataSource instanceof Image) {
				for (FileSystem fileSystem : getFileSystems((Image) dataSource)) {
					statement.setString(1, fileName.toLowerCase());
					statement.setString(2, "%" + dirName.toLowerCase() + "%");
					statement.setLong(3, fileSystem.getId());
					rs = connection.executeQuery(statement);		
					files.addAll(resultSetToAbstractFiles(rs));
				}
			} else if (dataSource instanceof VirtualDirectory) {
				statement.setString(1, fileName.toLowerCase());
				statement.setString(2, "%" + dirName.toLowerCase() + "%");
				statement.setLong(3, dataSource.getId());
				rs = connection.executeQuery(statement);		
				files = resultSetToAbstractFiles(rs);
			} else {
				final String msg = "Error, data source should be Image or VirtualDirectory, got: " + dataSource;
				logger.log(Level.SEVERE, msg);
				throw new IllegalArgumentException(msg);
			}
		} catch (SQLException e) {
			throw new TskCoreException("Error finding files in the data source by name, ", e);
		} finally {
			if (rs != null) {
				try {
					rs.close();
				} catch (SQLException ex) {
					logger.log(Level.WARNING, "Error closing result set after finding files", ex);
				}
			}
			releaseSharedLock();
		}
		return files;
	}

	/**
	 * Add a path (such as a local path) for a content object to tsk_file_paths
	 *
	 * @param objId object id of the file to add the path for
	 * @param path the path to add
	 * @throws SQLException exception thrown when database error occurred and
	 * path was not added
	 */
	private void addFilePath(long objId, String path) throws SQLException {
		CaseDbConnection connection = connections.getConnection();
		PreparedStatement statement = connection.getPreparedStatement(CaseDbConnection.PREPARED_STATEMENT.INSERT_LOCAL_PATH);			
		statement.clearParameters();
		statement.setLong(1, objId);
		statement.setString(2, path);
		connection.executeUpdate(statement);
	}

	/**
	 * wraps the version of addVirtualDirectory that takes a Transaction in a
	 * transaction local to this method
	 *
	 * @param parentId
	 * @param directoryName
	 * @return
	 * @throws TskCoreException
	 */
	public VirtualDirectory addVirtualDirectory(long parentId, String directoryName) throws TskCoreException {
		LogicalFileTransaction localTrans = createTransaction();
		VirtualDirectory newVD = addVirtualDirectory(parentId, directoryName, localTrans);
		localTrans.commit();
		return newVD;
	}

	/**
	 * Adds a virtual directory to the database and returns a VirtualDirectory
	 * object representing it.
	 *
	 * todo: at the moment we trust the transaction and don't do anything to
	 * check it is valid or in the correct state. we should.
	 *
	 * @param parentId the ID of the parent, or 0 if NULL
	 * @param directoryName the name of the virtual directory to create
	 * @param trans the transaction that will take care of locking and unlocking
	 * the database
	 * @return a VirtualDirectory object representing the one added to the
	 * database.
	 * @throws TskCoreException
	 */
	public VirtualDirectory addVirtualDirectory(long parentId, String directoryName, Transaction trans) throws TskCoreException {
		// get the parent path
		String parentPath = getFileParentPath(parentId);
		if (parentPath == null) {
			parentPath = "";
		}
		String parentName = getFileName(parentId);
		if (parentName != null) {
			parentPath = parentPath + "/" + parentName;
		}

		VirtualDirectory vd = null;

		//don't need to lock database or setAutoCommit(false), since we are
		//passed Transaction which handles that.

		//get last object id
		//create tsk_objects object with new id
		//create tsk_files object with the new id
		try {
			CaseDbConnection connection = connections.getConnection();

			long newObjId = getLastObjectId() + 1;
			if (newObjId < 1) {
				throw new TskCoreException("Error creating a virtual directory, cannot get new id of the object.");
			}

			//tsk_objects
			PreparedStatement statement = connection.getPreparedStatement(CaseDbConnection.PREPARED_STATEMENT.INSERT_OBJECT);			
			statement.clearParameters();
			statement.setLong(1, newObjId);
			if (parentId != 0) {
				statement.setLong(2, parentId);
			}
			statement.setLong(3, TskData.ObjectType.ABSTRACTFILE.getObjectType());
			connection.executeUpdate(statement);

			//tsk_files
			//obj_id, fs_obj_id, name, type, has_path, dir_type, meta_type, dir_flags, meta_flags, size, parent_path

			//obj_id, fs_obj_id, name
			statement = connection.getPreparedStatement(CaseDbConnection.PREPARED_STATEMENT.INSERT_FILE);			
			statement.clearParameters();
			statement.setLong(1, newObjId);

			// If the parent is part of a file system, grab its file system ID
			long parentFs = this.getFileSystemId(parentId);
			if (parentFs != -1) {
				statement.setLong(2, parentFs);
			}
			statement.setString(3, directoryName);

			//type, has_path
			statement.setShort(4, TskData.TSK_DB_FILES_TYPE_ENUM.VIRTUAL_DIR.getFileType());
			statement.setBoolean(5, true);

			//flags
			final TSK_FS_NAME_TYPE_ENUM dirType = TSK_FS_NAME_TYPE_ENUM.DIR;
			statement.setShort(6, dirType.getValue());
			final TSK_FS_META_TYPE_ENUM metaType = TSK_FS_META_TYPE_ENUM.TSK_FS_META_TYPE_DIR;
			statement.setShort(7, metaType.getValue());

			//note: using alloc under assumption that derived files derive from alloc files
			final TSK_FS_NAME_FLAG_ENUM dirFlag = TSK_FS_NAME_FLAG_ENUM.ALLOC;
			statement.setShort(8, dirFlag.getValue());
			final short metaFlags = (short) (TSK_FS_META_FLAG_ENUM.ALLOC.getValue()
					| TSK_FS_META_FLAG_ENUM.USED.getValue());
			statement.setShort(9, metaFlags);

			//size
			long size = 0;
			statement.setLong(10, size);

			//parent path
			statement.setString(15, parentPath);

			connection.executeUpdate(statement);

			vd = new VirtualDirectory(this, newObjId, directoryName, dirType,
					metaType, dirFlag, metaFlags, size, null, FileKnown.UNKNOWN,
					parentPath);
		} catch (SQLException e) {
			// we log this and rethrow it because the later finally clauses were also 
			// throwing an exception and this one got lost
			logger.log(Level.SEVERE, "Error creating virtual directory: " + directoryName, e);
			throw new TskCoreException("Error creating virtual directory '" + directoryName + "'", e);
		} 
		return vd;
	} 
	
	/**
	 * Get IDs of the virtual folder roots (at the same level as image), used
	 * for containers such as for local files.
	 *
	 * @return IDs of virtual directory root objects.
	 * @throws org.sleuthkit.datamodel.TskCoreException
	 */
	public List<VirtualDirectory> getVirtualDirectoryRoots() throws TskCoreException {
		acquireSharedLock();
		Statement s = null;				
		ResultSet rs = null;			
		try {
			CaseDbConnection connection = connections.getConnection();			
			s = connection.createStatement();
			rs = connection.executeQuery(s, "SELECT tsk_files.* FROM tsk_objects, tsk_files WHERE "
					+ "tsk_objects.par_obj_id IS NULL AND "
					+ "tsk_objects.type = " + TskData.ObjectType.ABSTRACTFILE.getObjectType() + " AND "
					+ "tsk_objects.obj_id = tsk_files.obj_id AND "
					+ "tsk_files.type = " + TskData.TSK_DB_FILES_TYPE_ENUM.VIRTUAL_DIR.getFileType() 
					+ " ORDER BY tsk_files.dir_type, tsk_files.name COLLATE NOCASE");
			List<VirtualDirectory> virtDirRootIds = new ArrayList<VirtualDirectory>();
			while (rs.next()) {
				virtDirRootIds.add(rsHelper.virtualDirectory(rs));
			}
			return virtDirRootIds;
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting local files virtual folder id", ex);
		} finally {
			closeResultSet(rs);			
			closeStatement(s);
			releaseSharedLock();
		}
	}

	/**
	 * @param id an image, volume or file system ID
	 * @return the ID of the '$CarvedFiles' directory for the given systemId
	 */
	private long getCarvedDirectoryId(long id) throws TskCoreException {
		long ret = 0;

		//use lock to ensure atomic cache check and connection/cache update
		acquireExclusiveLock();

		try {
			// first, check the cache
			Long carvedDirId = systemIdMap.get(id);
			if (carvedDirId != null) {
				return carvedDirId;
			}

			// it's not in the cache. Go to the DB
			// determine if we've got a volume system or file system ID
			Content parent = getContentById(id);
			if (parent == null) {
				throw new TskCoreException("No Content object found with this ID (" + id + ").");
			}

			List<Content> children = Collections.<Content>emptyList();
			if (parent instanceof FileSystem) {
				FileSystem fs = (FileSystem) parent;
				children = fs.getRootDirectory().getChildren();
			} else if (parent instanceof Volume
					|| parent instanceof Image) {
				children = parent.getChildren();
			} else {
				throw new TskCoreException("The given ID (" + id + ") was not an image, volume or file system.");
			}

			// see if any of the children are a '$CarvedFiles' directory
			Content carvedFilesDir = null;
			for (Content child : children) {
				if (child.getName().equals(VirtualDirectory.NAME_CARVED)) {
					carvedFilesDir = child;
					break;
				}
			}

			// if we found it, add it to the cache and return its ID
			if (carvedFilesDir != null) {

				// add it to the cache
				systemIdMap.put(id, carvedFilesDir.getId());

				return carvedFilesDir.getId();
			}

			// a carved files directory does not exist; create one
			VirtualDirectory vd = addVirtualDirectory(id, VirtualDirectory.NAME_CARVED);

			ret = vd.getId();
			// add it to the cache
			systemIdMap.put(id, ret);
		} finally {
			releaseExclusiveLock();
		}

		return ret;
	}

	/**
	 * Adds a carved file to the VirtualDirectory '$CarvedFiles' in the volume
	 * or file system given by systemId.
	 *
	 * @param carvedFileName the name of the carved file to add
	 * @param carvedFileSize the size of the carved file to add
	 * @param containerId the ID of the parent volume, file system, or image 
	 * @param data the layout information - a list of offsets that make up this
	 * carved file.
	 * @return A LayoutFile object representing the carved file.
	 * @throws org.sleuthkit.datamodel.TskCoreException
	 */
	public LayoutFile addCarvedFile(String carvedFileName, long carvedFileSize, long containerId, List<TskFileRange> data) throws TskCoreException {
		CaseDbConnection connection;
		try {
			connection = connections.getConnection();
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting case database connection", ex);
		} 
				
		// get the ID of the appropriate '$CarvedFiles' directory
		long carvedDirId = getCarvedDirectoryId(containerId);

		// get the parent path for the $CarvedFiles directory		
		String parentPath = getFileParentPath(carvedDirId);
		if (parentPath == null) {
			parentPath = "";
		}
		
		String parentName = getFileName(carvedDirId);
		if (parentName != null) {
			parentPath = parentPath + "/" + parentName;
		}

		acquireExclusiveLock();
		
		// we should cache this when we start adding lots of carved files...
		boolean isContainerAFs = false;
		Statement s = null;				
		ResultSet rs = null;			
		try {
			s = connection.createStatement();
			rs = connection.executeQuery(s, "select * from tsk_fs_info "
					+ "where obj_id = " + containerId);
			if (rs.next()) {
				isContainerAFs = true;
			}
		} catch (SQLException ex) {
			logger.log(Level.WARNING, "Error getting File System by ID", ex);
			closeResultSet(rs);			
			closeStatement(s);
		} 

		// all in one write lock and transaction
		// get last object id
		// create tsk_objects object with new id
		// create tsk_files object with the new id
		LayoutFile lf = null;
		try {
			connection.beginTransaction();
			
			long newObjId = getLastObjectId() + 1;
			if (newObjId < 1) {
				throw new TskCoreException("Error creating a virtual directory, cannot get new id of the object.");
			}

			//tsk_objects
			PreparedStatement statement = connection.getPreparedStatement(CaseDbConnection.PREPARED_STATEMENT.INSERT_OBJECT);	
			statement.clearParameters();
			statement.setLong(1, newObjId);
			statement.setLong(2, carvedDirId);
			statement.setLong(3, TskData.ObjectType.ABSTRACTFILE.getObjectType());
			connection.executeUpdate(statement);

			// tsk_files
			// obj_id, fs_obj_id, name, type, has_path, dir_type, meta_type, dir_flags, meta_flags, size, parent_path

			//obj_id, fs_obj_id, name
			statement = connection.getPreparedStatement(CaseDbConnection.PREPARED_STATEMENT.INSERT_FILE);			
			statement.clearParameters();
			statement.setLong(1, newObjId);
			
			// only insert into the fs_obj_id column if container is a FS
			if (isContainerAFs) {
				statement.setLong(2, containerId);
			}
			statement.setString(3, carvedFileName);

			// type
			final TSK_DB_FILES_TYPE_ENUM type = TSK_DB_FILES_TYPE_ENUM.CARVED;
			statement.setShort(4, type.getFileType());

			// has_path
			statement.setBoolean(5, true);

			// dirType
			final TSK_FS_NAME_TYPE_ENUM dirType = TSK_FS_NAME_TYPE_ENUM.REG;
			statement.setShort(6, dirType.getValue());

			// metaType
			final TSK_FS_META_TYPE_ENUM metaType = TSK_FS_META_TYPE_ENUM.TSK_FS_META_TYPE_REG;
			statement.setShort(7, metaType.getValue());

			// dirFlag
			final TSK_FS_NAME_FLAG_ENUM dirFlag = TSK_FS_NAME_FLAG_ENUM.UNALLOC;
			statement.setShort(8, dirFlag.getValue());

			// metaFlags
			final short metaFlags = TSK_FS_META_FLAG_ENUM.UNALLOC.getValue();
			statement.setShort(9, metaFlags);

			// size
			statement.setLong(10, carvedFileSize);

			// parent path
			statement.setString(15, parentPath);

			connection.executeUpdate(statement);

			// tsk_file_layout

			// add an entry in the tsk_layout_file table for each TskFileRange
			statement = connection.getPreparedStatement(CaseDbConnection.PREPARED_STATEMENT.INSERT_LAYOUT_FILE);			
			for (TskFileRange tskFileRange : data) {
				statement.clearParameters();
				
				// set the object ID
				statement.setLong(1, newObjId);

				// set byte_start
				statement.setLong(2, tskFileRange.getByteStart());

				// set byte_len
				statement.setLong(3, tskFileRange.getByteLen());

				// set the sequence number
				statement.setLong(4, tskFileRange.getSequence());

				// execute it
				connection.executeUpdate(statement);
			}

			connection.commitTransaction();
						
			// create the LayoutFile object
			lf = new LayoutFile(this, newObjId, carvedFileName, type, dirType,
					metaType, dirFlag, metaFlags, carvedFileSize, null,
					FileKnown.UNKNOWN, parentPath);

		} catch (SQLException e) {
			try {
				connection.rollbackTransaction();
			} catch (SQLException ex) {
				logger.log(Level.SEVERE, "Failed to rollback transaction", ex);				
			}
			throw new TskCoreException("Error creating a carved file '" + carvedFileName + "'", e);
		} finally {
			releaseExclusiveLock();
		}

		return lf;
	}

	/**
	 * Creates a new derived file object, adds it to database and returns it.
	 *
	 * TODO add support for adding derived method
	 *
	 * @param fileName file name the derived file
	 * @param localPath local path of the derived file, including the file name.
	 * The path is relative to the database path.
	 * @param size size of the derived file in bytes
	 * @param ctime
	 * @param crtime
	 * @param atime
	 * @param mtime
	 * @param isFile whether a file or directory, true if a file
	 * @param parentFile parent file object (derived or local file)
	 * @param rederiveDetails details needed to re-derive file (will be specific
	 * to the derivation method), currently unused
	 * @param toolName name of derivation method/tool, currently unused
	 * @param toolVersion version of derivation method/tool, currently unused
	 * @param otherDetails details of derivation method/tool, currently unused
	 * @return newly created derived file object
	 * @throws TskCoreException exception thrown if the object creation failed
	 * due to a critical system error
	 */
	public DerivedFile addDerivedFile(String fileName, String localPath,
			long size, long ctime, long crtime, long atime, long mtime,
			boolean isFile, AbstractFile parentFile,
			String rederiveDetails, String toolName, String toolVersion, String otherDetails) throws TskCoreException {
		CaseDbConnection connection;
		try {
			connection = connections.getConnection();
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting case database connection", ex);
		} 
						
		final long parentId = parentFile.getId();
		final String parentPath = parentFile.getParentPath() + parentFile.getName() + '/';
		
		DerivedFile ret = null;

		long newObjId = -1;

		acquireExclusiveLock();

		//all in one write lock and transaction
		//get last object id
		//create tsk_objects object with new id
		//create tsk_files object with the new id
		try {
			connection.beginTransaction();

			newObjId = getLastObjectId() + 1;
			if (newObjId < 1) {
				String msg = "Error creating a derived file, cannot get new id of the object, file name: " + fileName;
				throw new TskCoreException(msg);
			}

			//tsk_objects
			PreparedStatement statement = connection.getPreparedStatement(CaseDbConnection.PREPARED_STATEMENT.INSERT_OBJECT);			
			statement.clearParameters();
			statement.setLong(1, newObjId);
			statement.setLong(2, parentId);
			statement.setLong(3, TskData.ObjectType.ABSTRACTFILE.getObjectType());
			connection.executeUpdate(statement);

			//tsk_files
			//obj_id, fs_obj_id, name, type, has_path, dir_type, meta_type, dir_flags, meta_flags, size, parent_path

			//obj_id, fs_obj_id, name
			statement = connection.getPreparedStatement(CaseDbConnection.PREPARED_STATEMENT.INSERT_FILE);			
			statement.clearParameters();
			statement.setLong(1, newObjId);
			
			// If the parentFile is part of a file system, use its file system object ID.
			long fsObjId = this.getFileSystemId(parentId);
			if (fsObjId != -1) {
				statement.setLong(2, fsObjId);
			}
			statement.setString(3, fileName);

			//type, has_path
			statement.setShort(4, TskData.TSK_DB_FILES_TYPE_ENUM.DERIVED.getFileType());
			statement.setBoolean(5, true);

			//flags
			final TSK_FS_NAME_TYPE_ENUM dirType = isFile ? TSK_FS_NAME_TYPE_ENUM.REG : TSK_FS_NAME_TYPE_ENUM.DIR;
			statement.setShort(6, dirType.getValue());
			final TSK_FS_META_TYPE_ENUM metaType = isFile ? TSK_FS_META_TYPE_ENUM.TSK_FS_META_TYPE_REG : TSK_FS_META_TYPE_ENUM.TSK_FS_META_TYPE_DIR;
			statement.setShort(7, metaType.getValue());

			//note: using alloc under assumption that derived files derive from alloc files
			final TSK_FS_NAME_FLAG_ENUM dirFlag = TSK_FS_NAME_FLAG_ENUM.ALLOC;
			statement.setShort(8, dirFlag.getValue());
			final short metaFlags = (short) (TSK_FS_META_FLAG_ENUM.ALLOC.getValue()
					| TSK_FS_META_FLAG_ENUM.USED.getValue());
			statement.setShort(9, metaFlags);

			//size
			statement.setLong(10, size);
			//mactimes
			//long ctime, long crtime, long atime, long mtime,
			statement.setLong(11, ctime);
			statement.setLong(12, crtime);
			statement.setLong(13, atime);
			statement.setLong(14, mtime);
			//parent path
			statement.setString(15, parentPath);

			connection.executeUpdate(statement);

			//add localPath 
			addFilePath(newObjId, localPath);

			connection.commitTransaction();
						
			ret = new DerivedFile(this, newObjId, fileName, dirType, metaType, dirFlag, metaFlags,
					size, ctime, crtime, atime, mtime, null, null, parentPath, localPath, parentId);

			//TODO add derived method to tsk_files_derived and tsk_files_derived_method 
			return ret;
		} catch (SQLException e) {
			try {
				connection.rollbackTransaction();
			} catch (SQLException ex) {
				logger.log(Level.SEVERE, "Failed to rollback transaction", ex);				
			}
			String msg = "Error creating a derived file, file name: " + fileName;
			throw new TskCoreException(msg, e);
		} finally {
			releaseExclusiveLock();
		}
	}

	/**
	 *
	 * wraps the version of addLocalFile that takes a Transaction in a
	 * transaction local to this method.
	 *
	 * @param fileName
	 * @param localPath
	 * @param size
	 * @param ctime
	 * @param crtime
	 * @param atime
	 * @param mtime
	 * @param isFile
	 * @param parent
	 * @return
	 * @throws TskCoreException
	 */
	public LocalFile addLocalFile(String fileName, String localPath,
			long size, long ctime, long crtime, long atime, long mtime,
			boolean isFile, AbstractFile parent) throws TskCoreException {
		LogicalFileTransaction localTrans = createTransaction();
		LocalFile created = addLocalFile(fileName, localPath, size, ctime, crtime, atime, mtime, isFile, parent, localTrans);
		localTrans.commit();
		return created;
	}

	/**
	 * Creates a new local file object, adds it to database and returns it.
	 *
	 *
	 * todo: at the moment we trust the transaction and don't do anything to
	 * check it is valid or in the correct state. we should.
	 *
	 *
	 * @param fileName file name the derived file
	 * @param localPath local absolute path of the local file, including the
	 * file name.
	 * @param size size of the derived file in bytes
	 * @param ctime
	 * @param crtime
	 * @param atime
	 * @param mtime
	 * @param isFile whether a file or directory, true if a file
	 * @param parent parent file object (such as virtual directory, another
	 * local file, or FsContent type of file)
	 * @param trans the transaction that will take care of locking and unlocking
	 * the database
	 * @return newly created derived file object
	 * @throws TskCoreException exception thrown if the object creation failed
	 * due to a critical system error
	 */
	public LocalFile addLocalFile(String fileName, String localPath,
			long size, long ctime, long crtime, long atime, long mtime,
			boolean isFile, AbstractFile parent, Transaction trans) throws TskCoreException {
		CaseDbConnection connection;
		try {
			connection = connections.getConnection();
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting case database connection", ex);
		} 
								
		long parentId = -1;
		String parentPath;
		if (parent == null) {
			throw new TskCoreException("Error adding local file: " + fileName + ", parent to add to is null");
		} else {
			parentId = parent.getId();
			parentPath = parent.getParentPath() + "/" + parent.getName();
		}

		LocalFile ret = null;

		long newObjId = -1;

		//don't need to lock database or setAutoCommit(false), since we are
		//passed Transaction which handles that.

		//get last object id
		//create tsk_objects object with new id
		//create tsk_files object with the new id
		try {
			newObjId = getLastObjectId() + 1;
			if (newObjId < 1) {
				String msg = "Error creating a local file, cannot get new id of the object, file name: " + fileName;
				throw new TskCoreException(msg);
			}

			//tsk_objects
			PreparedStatement statement = connection.getPreparedStatement(CaseDbConnection.PREPARED_STATEMENT.INSERT_OBJECT);			
			statement.clearParameters();
			statement.setLong(1, newObjId);
			statement.setLong(2, parentId);
			statement.setLong(3, TskData.ObjectType.ABSTRACTFILE.getObjectType());
			connection.executeUpdate(statement);

			//tsk_files
			//obj_id, fs_obj_id, name, type, has_path, dir_type, meta_type, dir_flags, meta_flags, size, parent_path

			//obj_id, fs_obj_id, name
			statement = connection.getPreparedStatement(CaseDbConnection.PREPARED_STATEMENT.INSERT_FILE);			
			statement.clearParameters();
			statement.setLong(1, newObjId);
			// nothing to set for parameter 2, fs_obj_id since local files aren't part of file systems
			statement.setString(3, fileName);

			//type, has_path
			statement.setShort(4, TskData.TSK_DB_FILES_TYPE_ENUM.LOCAL.getFileType());
			statement.setBoolean(5, true);

			//flags
			final TSK_FS_NAME_TYPE_ENUM dirType = isFile ? TSK_FS_NAME_TYPE_ENUM.REG : TSK_FS_NAME_TYPE_ENUM.DIR;
			statement.setShort(6, dirType.getValue());
			final TSK_FS_META_TYPE_ENUM metaType = isFile ? TSK_FS_META_TYPE_ENUM.TSK_FS_META_TYPE_REG : TSK_FS_META_TYPE_ENUM.TSK_FS_META_TYPE_DIR;
			statement.setShort(7, metaType.getValue());

			//note: using alloc under assumption that derived files derive from alloc files
			final TSK_FS_NAME_FLAG_ENUM dirFlag = TSK_FS_NAME_FLAG_ENUM.ALLOC;
			statement.setShort(8, dirFlag.getValue());
			final short metaFlags = (short) (TSK_FS_META_FLAG_ENUM.ALLOC.getValue()
					| TSK_FS_META_FLAG_ENUM.USED.getValue());
			statement.setShort(9, metaFlags);

			//size
			statement.setLong(10, size);
			//mactimes
			//long ctime, long crtime, long atime, long mtime,
			statement.setLong(11, ctime);
			statement.setLong(12, crtime);
			statement.setLong(13, atime);
			statement.setLong(14, mtime);
			//parent path
			statement.setString(15, parentPath);

			connection.executeUpdate(statement);

			//add localPath 
			addFilePath(newObjId, localPath);

			return new LocalFile(this, newObjId, fileName, dirType, metaType, dirFlag, metaFlags,
					size, ctime, crtime, atime, mtime, null, null, parentPath, localPath, parentId);
		} catch (SQLException e) {
			String msg = "Error creating a derived file, file name: " + fileName;
			throw new TskCoreException(msg, e);
		} 
	}

	/**
	 * Find all files in the data source, by name and parent
	 *
	 * @param dataSource the dataSource (Image, parent-less VirtualDirectory) to
	 * search for the given file name
	 * @param fileName Pattern of the name of the file or directory to match
 (case insensitive, used in LIKE SQL s).
	 * @param parentFile Object for parent file/directory to find children in
	 * @return a list of AbstractFile for files/directories whose name matches
	 * fileName and that were inside a directory described by parentFile.
	 */
	public List<AbstractFile> findFiles(Content dataSource, String fileName, AbstractFile parentFile) throws TskCoreException {
		return findFiles(dataSource, fileName, parentFile.getName());
	}

	/**
	 * Count files matching the specific Where clause
	 *
	 * @param sqlWhereClause a SQL where clause appropriate for the desired
	 * files (do not begin the WHERE clause with the word WHERE!)
	 * @return count of files each of which satisfy the given WHERE clause
	 * @throws TskCoreException
	 */
	public long countFilesWhere(String sqlWhereClause) throws TskCoreException {
		acquireSharedLock();
		Statement s = null;				
		ResultSet rs = null;			
		try {
			CaseDbConnection connection = connections.getConnection();			
			s = connection.createStatement();
			rs = connection.executeQuery(s, "SELECT COUNT (*) FROM tsk_files WHERE " + sqlWhereClause);
			return rs.getLong(1);
		} catch (SQLException e) {
			throw new TskCoreException("SQLException thrown when calling 'SleuthkitCase.findFilesWhere().", e);
		} finally {
			closeResultSet(rs);			
			closeStatement(s);
			releaseSharedLock();
		}
	}

	/**
	 * Find and return list of all (abstract) files matching the specific Where
	 * clause
	 *
	 * @param sqlWhereClause a SQL where clause appropriate for the desired
	 * files (do not begin the WHERE clause with the word WHERE!)
	 * @return a list of AbstractFile each of which satisfy the given WHERE
	 * clause
	 * @throws TskCoreException
	 */
	public List<AbstractFile> findAllFilesWhere(String sqlWhereClause) throws TskCoreException {
		acquireSharedLock();
		Statement s = null;				
		ResultSet rs = null;			
		try {
			CaseDbConnection connection = connections.getConnection();			
			s = connection.createStatement();
			rs = connection.executeQuery(s, "SELECT * FROM tsk_files WHERE " + sqlWhereClause);
			return resultSetToAbstractFiles(rs);
		} catch (SQLException e) {
			throw new TskCoreException("SQLException thrown when calling 'SleuthkitCase.findAllFilesWhere(): " + sqlWhereClause, e);
		} finally {
			closeResultSet(rs);			
			closeStatement(s);
			releaseSharedLock();
		}
	}

	/**
	 * Find and return list of all (abstract) ids of files matching the specific
	 * Where clause
	 *
	 * @param sqlWhereClause a SQL where clause appropriate for the desired
	 * files (do not begin the WHERE clause with the word WHERE!)
	 * @return a list of file ids each of which satisfy the given WHERE clause
	 * @throws TskCoreException
	 */
	public List<Long> findAllFileIdsWhere(String sqlWhereClause) throws TskCoreException {
		acquireSharedLock();
		Statement s = null;				
		ResultSet rs = null;			
		try {
			CaseDbConnection connection = connections.getConnection();			
			s = connection.createStatement();
			rs = connection.executeQuery(s, "SELECT obj_id FROM tsk_files WHERE " + sqlWhereClause);
			List<Long> ret = new ArrayList<Long>();
			while (rs.next()) {
				ret.add(rs.getLong(1));
			}
			return ret;
		} catch (SQLException e) {
			throw new TskCoreException("SQLException thrown when calling 'SleuthkitCase.findAllFileIdsWhere(): " + sqlWhereClause, e);
		} finally {
			closeResultSet(rs);			
			closeStatement(s);
			releaseSharedLock();
		}
	}

	/**
	 * Find and return list of files matching the specific Where clause
	 *
	 * @param sqlWhereClause a SQL where clause appropriate for the desired
	 * files (do not begin the WHERE clause with the word WHERE!)
	 * @return a list of FsContent each of which satisfy the given WHERE clause
	 * @throws TskCoreException
	 */
	public List<FsContent> findFilesWhere(String sqlWhereClause) throws TskCoreException {
		acquireSharedLock();
		Statement s = null;				
		ResultSet rs = null;			
		try {
			CaseDbConnection connection = connections.getConnection();			
			s = connection.createStatement();
			rs = connection.executeQuery(s, "SELECT * FROM tsk_files WHERE " + sqlWhereClause);
			return resultSetToFsContents(rs);
		} catch (SQLException e) {
			throw new TskCoreException("SQLException thrown when calling 'SleuthkitCase.findFilesWhere().", e);
		} finally {
			closeResultSet(rs);			
			closeStatement(s);
			releaseSharedLock();
		}
	}

	/**
	 * @param dataSource the data source (Image, VirtualDirectory for file-sets,
	 * etc) to search for the given file name
	 * @param filePath The full path to the file(s) of interest. This can
	 * optionally include the image and volume names. Treated in a case-
	 * insensitive manner.
	 * @return a list of AbstractFile that have the given file path.
	 */
	public List<AbstractFile> openFiles(Content dataSource, String filePath) throws TskCoreException {

		// get the non-unique path (strip of image and volume path segments, if
		// the exist.
		String path = AbstractFile.createNonUniquePath(filePath).toLowerCase();

		// split the file name from the parent path
		int lastSlash = path.lastIndexOf("/");

		// if the last slash is at the end, strip it off
		if (lastSlash == path.length()) {
			path = path.substring(0, lastSlash - 1);
			lastSlash = path.lastIndexOf("/");
		}

		String parentPath = path.substring(0, lastSlash);
		String fileName = path.substring(lastSlash);

		return findFiles(dataSource, fileName, parentPath);
	}

	/**
	 * Get file layout ranges from tsk_file_layout, for a file with specified id
	 *
	 * @param id of the file to get file layout ranges for
	 * @return list of populated file ranges
	 * @throws TskCoreException thrown if a critical error occurred within tsk
	 * core
	 */
	public List<TskFileRange> getFileRanges(long id) throws TskCoreException {
		acquireSharedLock();
		Statement s = null;				
		ResultSet rs = null;			
		try {
			CaseDbConnection connection = connections.getConnection();			
			s = connection.createStatement();
			rs = connection.executeQuery(s, "select * from tsk_file_layout where obj_id = " + id + " order by sequence");
			List<TskFileRange> ranges = new ArrayList<TskFileRange>();
			while (rs.next()) {
				ranges.add(rsHelper.tskFileRange(rs));
			}
			return ranges;
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting TskFileLayoutRanges by ID.", ex);
		} finally {
			closeResultSet(rs);			
			closeStatement(s);
			releaseSharedLock();
		}
	}

	/**
	 * Get am image by the image object id
	 *
	 * @param id of the image object
	 * @return Image object populated
	 * @throws TskCoreException thrown if a critical error occurred within tsk
	 * core
	 */
	public Image getImageById(long id) throws TskCoreException {
		acquireSharedLock();
		Statement s1 = null;				
		ResultSet rs1 = null;			
		Statement s2 = null;				
		ResultSet rs2 = null;			
		try {
			CaseDbConnection connection = connections.getConnection();
			s1 = connection.createStatement();
			rs1 = connection.executeQuery(s1, "SELECT * FROM tsk_image_info WHERE obj_id = " + id);
			if (rs1.next()) {
				s2 = connection.createStatement();
				rs2 = connection.executeQuery(s2, "select * from tsk_image_names where obj_id = " + rs1.getLong("obj_id"));
				List<String> imagePaths = new ArrayList<String>();
				while (rs2.next()) {
					imagePaths.add(rsHelper.imagePath(rs2));
				}
				return rsHelper.image(rs1, imagePaths.toArray(new String[imagePaths.size()]));
			} else {
				throw new TskCoreException("No image found for id: " + id);
			}
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting Image by ID", ex);
		} finally {
			closeResultSet(rs2);			
			closeStatement(s2);
			closeResultSet(rs1);			
			closeStatement(s1);
			releaseSharedLock();
		}
	}

	/**
	 * Get a volume system by the volume system object id
	 *
	 * @param id id of the volume system
	 * @param parent image containing the volume system
	 * @return populated VolumeSystem object
	 * @throws TskCoreException thrown if a critical error occurred within tsk
	 * core
	 */
	VolumeSystem getVolumeSystemById(long id, Image parent) throws TskCoreException {
		acquireSharedLock();
		Statement s = null;				
		ResultSet rs = null;			
		try {
			CaseDbConnection connection = connections.getConnection();			
			s = connection.createStatement();
			rs = connection.executeQuery(s, "select * from tsk_vs_info "
					+ "where obj_id = " + id);
			if (rs.next()) {
				return rsHelper.volumeSystem(rs, parent);
			} else {
				throw new TskCoreException("No volume system found for id:" + id);
			}
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting Volume System by ID.", ex);
		} finally {
			closeResultSet(rs);			
			closeStatement(s);
			releaseSharedLock();
		}
	}

	/**
	 * @param id ID of the desired VolumeSystem
	 * @param parentId ID of the VolumeSystem's parent
	 * @return the VolumeSystem with the given ID
	 * @throws TskCoreException
	 */
	VolumeSystem getVolumeSystemById(long id, long parentId) throws TskCoreException {
		VolumeSystem vs = getVolumeSystemById(id, null);
		vs.setParentId(parentId);
		return vs;
	}

	/**
	 * Get a file system by the object id
	 *
	 * @param id of the filesystem
	 * @param parent parent Image of the file system
	 * @return populated FileSystem object
	 * @throws TskCoreException thrown if a critical error occurred within tsk
	 * core
	 */
	FileSystem getFileSystemById(long id, Image parent) throws TskCoreException {
		return getFileSystemByIdHelper(id, parent);
	}

	/**
	 * @param id ID of the desired FileSystem
	 * @param parentId ID of the FileSystem's parent
	 * @return the desired FileSystem
	 * @throws TskCoreException
	 */
	FileSystem getFileSystemById(long id, long parentId) throws TskCoreException {
		Volume vol = null;
		FileSystem fs = getFileSystemById(id, vol);
		fs.setParentId(parentId);
		return fs;
	}

	/**
	 * Get a file system by the object id
	 *
	 * @param id of the filesystem
	 * @param parent parent Volume of the file system
	 * @return populated FileSystem object
	 * @throws TskCoreException thrown if a critical error occurred within tsk
	 * core
	 */
	FileSystem getFileSystemById(long id, Volume parent) throws TskCoreException {
		return getFileSystemByIdHelper(id, parent);
	}

	/**
	 * Get file system by id and Content parent
	 *
	 * @param id of the filesystem to get
	 * @param parent a direct parent Content object
	 * @return populated FileSystem object
	 * @throws TskCoreException thrown if a critical error occurred within tsk
	 * core
	 */
	private FileSystem getFileSystemByIdHelper(long id, Content parent) throws TskCoreException {
		// see if we already have it
		// @@@ NOTE: this is currently kind of bad in that we are ignoring the parent value,
		// but it should be the same...
		synchronized (fileSystemIdMap) {
			if (fileSystemIdMap.containsKey(id)) {
				return fileSystemIdMap.get(id);
			}
		}		
		acquireSharedLock();
		Statement s = null;				
		ResultSet rs = null;			
		try {
			CaseDbConnection connection = connections.getConnection();			
			s = connection.createStatement();
			rs = connection.executeQuery(s, "select * from tsk_fs_info "
					+ "where obj_id = " + id);
			if (rs.next()) {
				FileSystem fs = rsHelper.fileSystem(rs, parent);
				// save it for the next call
				synchronized(fileSystemIdMap) {
					fileSystemIdMap.put(id, fs);
				}
				return fs;
			} else {
				throw new TskCoreException("No file system found for id:" + id);
			}
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting File System by ID", ex);
		} finally {
			closeResultSet(rs);			
			closeStatement(s);
			releaseSharedLock();
		}
	}

	/**
	 * Get volume by id
	 *
	 * @param id
	 * @param parent volume system
	 * @return populated Volume object
	 * @throws TskCoreException thrown if a critical error occurred within tsk
	 * core
	 */
	Volume getVolumeById(long id, VolumeSystem parent) throws TskCoreException {
		acquireSharedLock();
		Statement s = null;				
		ResultSet rs = null;			
		try {
			CaseDbConnection connection = connections.getConnection();			
			s = connection.createStatement();
			rs = connection.executeQuery(s, "select * from tsk_vs_parts "
					+ "where obj_id = " + id);
			if (rs.next()) {
				return rsHelper.volume(rs, parent);
			} else {
				throw new TskCoreException("No volume found for id:" + id);
			}
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting Volume by ID", ex);
		} finally {
			closeResultSet(rs);			
			closeStatement(s);
			releaseSharedLock();
		}
	}

	/**
	 * @param id ID of the desired Volume
	 * @param parentId ID of the Volume's parent
	 * @return the desired Volume
	 * @throws TskCoreException
	 */
	Volume getVolumeById(long id, long parentId) throws TskCoreException {
		Volume vol = getVolumeById(id, null);
		vol.setParentId(parentId);
		return vol;
	}

	/**
	 * Get a directory by id
	 *
	 * @param id of the directory object
	 * @param parentFs parent file system
	 * @return populated Directory object
	 * @throws TskCoreException thrown if a critical error occurred within tsk
	 * core
	 */
	Directory getDirectoryById(long id, FileSystem parentFs) throws TskCoreException {
		acquireSharedLock();
		Statement s = null;				
		ResultSet rs = null;			
		try {
			CaseDbConnection connection = connections.getConnection();			
			s = connection.createStatement();
			rs = connection.executeQuery(s, "SELECT * FROM tsk_files "
					+ "WHERE obj_id = " + id);
			Directory temp = null;
			if (rs.next()) {
				final short type = rs.getShort("type");
				if (type == TSK_DB_FILES_TYPE_ENUM.FS.getFileType()) {
					if (rs.getShort("meta_type") == TSK_FS_META_TYPE_ENUM.TSK_FS_META_TYPE_DIR.getValue()) {
						temp = rsHelper.directory(rs, parentFs);
					}
				} else if (type == TSK_DB_FILES_TYPE_ENUM.VIRTUAL_DIR.getFileType()) {
					throw new TskCoreException("Expecting an FS-type directory, got virtual, id: " + id);
				}
			} else {
				throw new TskCoreException("No Directory found for id:" + id);
			}
			return temp;
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting Directory by ID", ex);
		} finally {
			closeResultSet(rs);			
			closeStatement(s);
			releaseSharedLock();
		}
	}

	/**
	 * Helper to return FileSystems in an Image
	 *
	 * @param image Image to lookup FileSystem for
	 * @return Collection of FileSystems in the image
	 */
	public Collection<FileSystem> getFileSystems(Image image) {
		acquireSharedLock();
		List<FileSystem> fileSystems = new ArrayList<FileSystem>();
		Statement s  = null;
		ResultSet rs = null;
		try {			
			CaseDbConnection connection = connections.getConnection();		
			s  = connection.createStatement();
						
			// Get all the file systems.
			List<FileSystem> allFileSystems = new ArrayList<FileSystem>();
			try {
				rs = connection.executeQuery(s, "SELECT * FROM tsk_fs_info");
				while (rs.next()) {
					allFileSystems.add(rsHelper.fileSystem(rs, null));
				}
			} catch (SQLException ex) {
				logger.log(Level.SEVERE, "There was a problem while trying to obtain all file systems", ex);
			} finally {
				closeResultSet(rs);
				rs = null;
			}			
						
			// For each file system, find the image to which it belongs by iteratively
			// climbing the tsk_ojbects hierarchy only taking those file systems
			// that belong to this image.
			for (FileSystem fs : allFileSystems) {
				Long imageID = null;
				Long currentObjID = fs.getId();
				while (imageID == null) {
					try {
						rs = connection.executeQuery(s, "SELECT * FROM tsk_objects WHERE tsk_objects.obj_id = " + currentObjID);
						currentObjID = rs.getLong("par_obj_id");
						if (rs.getInt("type") == TskData.ObjectType.IMG.getObjectType()) {
							imageID = rs.getLong("obj_id");
						}
					} catch (SQLException ex) {
						logger.log(Level.SEVERE, "There was a problem while trying to obtain this image's file systems", ex);
					} finally {
						closeResultSet(rs);
						rs = null;						
					}
				}

				// see if imageID is this image's ID
				if (imageID == image.getId()) {
					fileSystems.add(fs);
				}
			}						
		} catch (SQLException ex) {
			logger.log(Level.SEVERE, "Error getting case database connection", ex);
		} finally {
			closeResultSet(rs);			
			closeStatement(s);
			releaseSharedLock();
		}
		return fileSystems;
	}

	/**
	 * Returns the list of direct children for a given Image
	 *
	 * @param img image to get children for
	 * @return list of Contents (direct image children)
	 * @throws TskCoreException thrown if a critical error occurred within tsk
	 * core
	 */
	List<Content> getImageChildren(Image img) throws TskCoreException {
		Collection<ObjectInfo> childInfos = getChildrenInfo(img);
		List<Content> children = new ArrayList<Content>();
		for (ObjectInfo info : childInfos) {
			if (info.type == ObjectType.VS) {
				children.add(getVolumeSystemById(info.id, img));
			} else if (info.type == ObjectType.FS) {
				children.add(getFileSystemById(info.id, img));
			} else if (info.type == ObjectType.ABSTRACTFILE) {
				children.add(getAbstractFileById(info.id));
			} else {
				throw new TskCoreException("Image has child of invalid type: " + info.type);
			}
		}
		return children;
	}

	/**
	 * Returns the list of direct children IDs for a given Image
	 *
	 * @param img image to get children for
	 * @return list of IDs (direct image children)
	 * @throws TskCoreException thrown if a critical error occurred within tsk
	 * core
	 */
	List<Long> getImageChildrenIds(Image img) throws TskCoreException {
		Collection<ObjectInfo> childInfos = getChildrenInfo(img);
		List<Long> children = new ArrayList<Long>();
		for (ObjectInfo info : childInfos) {
			if (info.type == ObjectType.VS
					|| info.type == ObjectType.FS
					|| info.type == ObjectType.ABSTRACTFILE) {
				children.add(info.id);
			} else {
				throw new TskCoreException("Image has child of invalid type: " + info.type);
			}
		}
		return children;
	}

	/**
	 * Returns the list of direct children for a given VolumeSystem
	 *
	 * @param vs volume system to get children for
	 * @return list of volume system children objects
	 * @throws TskCoreException thrown if a critical error occurred within tsk
	 * core
	 */
	List<Content> getVolumeSystemChildren(VolumeSystem vs) throws TskCoreException {
		Collection<ObjectInfo> childInfos = getChildrenInfo(vs);
		List<Content> children = new ArrayList<Content>();
		for (ObjectInfo info : childInfos) {
			if (info.type == ObjectType.VOL) {
				children.add(getVolumeById(info.id, vs));
			} else if (info.type == ObjectType.ABSTRACTFILE) {
				children.add(getAbstractFileById(info.id));
			} else {
				throw new TskCoreException("VolumeSystem has child of invalid type: " + info.type);
			}
		}
		return children;
	}

	/**
	 * Returns the list of direct children IDs for a given VolumeSystem
	 *
	 * @param vs volume system to get children for
	 * @return list of volume system children IDs
	 * @throws TskCoreException thrown if a critical error occurred within tsk
	 * core
	 */
	List<Long> getVolumeSystemChildrenIds(VolumeSystem vs) throws TskCoreException {
		Collection<ObjectInfo> childInfos = getChildrenInfo(vs);
		List<Long> children = new ArrayList<Long>();
		for (ObjectInfo info : childInfos) {
			if (info.type == ObjectType.VOL || info.type == ObjectType.ABSTRACTFILE) {
				children.add(info.id);
			} else {
				throw new TskCoreException("VolumeSystem has child of invalid type: " + info.type);
			}
		}
		return children;
	}

	/**
	 * Returns a list of direct children for a given Volume
	 *
	 * @param vol volume to get children of
	 * @return list of Volume children
	 * @throws TskCoreException thrown if a critical error occurred within tsk
	 * core
	 */
	List<Content> getVolumeChildren(Volume vol) throws TskCoreException {
		Collection<ObjectInfo> childInfos = getChildrenInfo(vol);
		List<Content> children = new ArrayList<Content>();
		for (ObjectInfo info : childInfos) {
			if (info.type == ObjectType.FS) {
				children.add(getFileSystemById(info.id, vol));
			} else if (info.type == ObjectType.ABSTRACTFILE) {
				children.add(getAbstractFileById(info.id));
			} else {
				throw new TskCoreException("Volume has child of invalid type: " + info.type);
			}
		}
		return children;
	}

	/**
	 * Returns a list of direct children IDs for a given Volume
	 *
	 * @param vol volume to get children of
	 * @return list of Volume children IDs
	 * @throws TskCoreException thrown if a critical error occurred within tsk
	 * core
	 */
	List<Long> getVolumeChildrenIds(Volume vol) throws TskCoreException {
		final Collection<ObjectInfo> childInfos = getChildrenInfo(vol);
		final List<Long> children = new ArrayList<Long>();
		for (ObjectInfo info : childInfos) {
			if (info.type == ObjectType.FS || info.type == ObjectType.ABSTRACTFILE) {
				children.add(info.id);
			} else {
				throw new TskCoreException("Volume has child of invalid type: " + info.type);
			}
		}
		return children;
	}


	/**
	 * Returns a map of image object IDs to a list of fully qualified file paths
	 * for that image
	 *
	 * @return map of image object IDs to file paths
	 * @throws TskCoreException thrown if a critical error occurred within tsk
	 * core
	 */
	public Map<Long, List<String>> getImagePaths() throws TskCoreException {
		acquireSharedLock();
		Statement s1 = null;				
		Statement s2 = null;				
		ResultSet rs1 = null;			
		ResultSet rs2 = null;			
		try {
			CaseDbConnection connection = connections.getConnection();
			s1 = connection.createStatement();
			s2 = connection.createStatement();
			rs1 = connection.executeQuery(s1, "select obj_id from tsk_image_info");
			Map<Long, List<String>> imgPaths = new LinkedHashMap<Long, List<String>>();
			while (rs1.next()) {
				long obj_id = rs1.getLong("obj_id");
				rs2 = connection.executeQuery(s2, "select * from tsk_image_names where obj_id = " + obj_id);
				List<String> paths = new ArrayList<String>();
				while (rs2.next()) {
					paths.add(rsHelper.imagePath(rs2));
				}
				rs2.close();
				rs2 = null;
				imgPaths.put(obj_id, paths);
			}
			return imgPaths;
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting image paths.", ex);
		} finally {
			closeResultSet(rs2);			
			closeStatement(s2);
			closeResultSet(rs1);			
			closeStatement(s1);
			releaseSharedLock();
		}
	}

	/**
	 * @return a collection of Images associated with this instance of
	 * SleuthkitCase
	 * @throws TskCoreException
	 */
	public List<Image> getImages() throws TskCoreException {
		acquireSharedLock();
		Statement s = null;				
		ResultSet rs = null;			
		try {
			CaseDbConnection connection = connections.getConnection();			
			s = connection.createStatement();
			rs = connection.executeQuery(s, "SELECT obj_id FROM tsk_image_info");
			Collection<Long> imageIDs = new ArrayList<Long>();
			while (rs.next()) {
				imageIDs.add(rs.getLong("obj_id"));
			}
			List<Image> images = new ArrayList<Image>();
			for (long id : imageIDs) {
				images.add(getImageById(id));
			}
			return images;
		} catch (SQLException ex) {
			throw new TskCoreException("Error retrieving images.", ex);
		} finally {
			closeResultSet(rs);			
			closeStatement(s);
			releaseSharedLock();
		}
	}
	
	/**
	 * Get last (max) object id of content object in tsk_objects.
	 *
	 * Note, if you are using this id to create a new object, make sure you are
	 * getting and using it in the same write lock/transaction to avoid
	 * potential concurrency issues with other writes
	 *
	 * @return currently max id
	 * @throws TskCoreException exception thrown when database error occurs and
	 * last object id could not be queried
	 */
	public long getLastObjectId() throws TskCoreException { // TODO: This is not thread-safe
		acquireSharedLock();
		ResultSet rs = null;
		try {
			CaseDbConnection connection = connections.getConnection();
			PreparedStatement statement = connection.getPreparedStatement(CaseDbConnection.PREPARED_STATEMENT.SELECT_MAX_OBJECT_ID);
			rs = connection.executeQuery(statement);		
			long id = -1;
			if (rs.next()) {
				id = rs.getLong(1);
			}
			return id;
		} catch (SQLException e) {
			final String msg = "Error closing result set after getting last object id.";
			logger.log(Level.SEVERE, msg, e);
			throw new TskCoreException(msg, e);
		} finally {
			closeResultSet(rs);
			releaseSharedLock();
		}
	}

	/**
	 * Set the file paths for the image given by obj_id
	 *
	 * @param obj_id the ID of the image to update
	 * @param paths the fully qualified path to the files that make up the image
	 * @throws TskCoreException exception thrown when critical error occurs
	 * within tsk core and the update fails
	 */
	public void setImagePaths(long obj_id, List<String> paths) throws TskCoreException {
		acquireExclusiveLock();
		try {
			CaseDbConnection connection = connections.getConnection();
			connection.executeUpdate("DELETE FROM tsk_image_names WHERE obj_id = " + obj_id);
			for (int i = 0; i < paths.size(); i++) {
				connection.executeUpdate("INSERT INTO tsk_image_names VALUES (" + obj_id + ", \"" + paths.get(i) + "\", " + i + ")");
			}
		} catch (SQLException ex) {
			throw new TskCoreException("Error updating image paths.", ex);
		} finally {
			releaseExclusiveLock();
		}
	}

	/**
	 * Creates file object from a SQL query result set of rows from the
	 * tsk_files table. Assumes that the query was of the form "SELECT * FROM
	 * tsk_files WHERE XYZ".
	 *
	 * @param rs ResultSet to get content from. Caller is responsible for
	 * closing it.
	 * @return list of file objects from tsk_files table containing the results
	 * @throws SQLException if the query fails
	 */
	private List<AbstractFile> resultSetToAbstractFiles(ResultSet rs) throws SQLException {

		ArrayList<AbstractFile> results = new ArrayList<AbstractFile>();
		acquireSharedLock();
		try {
			while (rs.next()) {
				final short type = rs.getShort("type");
				if (type == TSK_DB_FILES_TYPE_ENUM.FS.getFileType()) {
					FsContent result;
					if (rs.getShort("meta_type") == TSK_FS_META_TYPE_ENUM.TSK_FS_META_TYPE_DIR.getValue()) {
						result = rsHelper.directory(rs, null);
					} else {
						result = rsHelper.file(rs, null);
					}
					results.add(result);
				} else if (type == TSK_DB_FILES_TYPE_ENUM.VIRTUAL_DIR.getFileType()) {
					final VirtualDirectory virtDir = rsHelper.virtualDirectory(rs);
					results.add(virtDir);
				} else if (type == TSK_DB_FILES_TYPE_ENUM.UNALLOC_BLOCKS.getFileType()
						|| type == TSK_DB_FILES_TYPE_ENUM.CARVED.getFileType()) {
					TSK_DB_FILES_TYPE_ENUM atype = TSK_DB_FILES_TYPE_ENUM.valueOf(type);
					String parentPath = rs.getString("parent_path");
					if (parentPath == null) {
						parentPath = "";
					}
					LayoutFile lf = new LayoutFile(this, rs.getLong("obj_id"),
							rs.getString("name"),
							atype,
							TSK_FS_NAME_TYPE_ENUM.valueOf(rs.getShort("dir_type")), TSK_FS_META_TYPE_ENUM.valueOf(rs.getShort("meta_type")),
							TSK_FS_NAME_FLAG_ENUM.valueOf(rs.getShort("dir_flags")), rs.getShort("meta_flags"),
							rs.getLong("size"),
							rs.getString("md5"), FileKnown.valueOf(rs.getByte("known")), parentPath);
					results.add(lf);
				} else if (type == TSK_DB_FILES_TYPE_ENUM.DERIVED.getFileType()) {
					final DerivedFile df;
					df = rsHelper.derivedFile(rs, AbstractContent.UNKNOWN_ID);
					results.add(df);
				} else if (type == TSK_DB_FILES_TYPE_ENUM.LOCAL.getFileType()) {
					final LocalFile lf;
					lf = rsHelper.localFile(rs, AbstractContent.UNKNOWN_ID);
					results.add(lf);
				}

			} //end for each rs
		} catch (SQLException e) {
			logger.log(Level.SEVERE, "Error getting abstract file from result set.", e);
		} finally {
			releaseSharedLock();
		}

		return results;
	}

	/**
	 * Creates FsContent objects from SQL query result set on tsk_files table
	 *
	 * @param rs the result set with the query results
	 * @return list of fscontent objects matching the query
	 * @throws SQLException if SQL query result getting failed
	 */
	private List<FsContent> resultSetToFsContents(ResultSet rs) throws SQLException {
		List<FsContent> results = new ArrayList<FsContent>();
		List<AbstractFile> temp = resultSetToAbstractFiles(rs);
		for (AbstractFile f : temp) {
			final TSK_DB_FILES_TYPE_ENUM type = f.getType();
			if (type.equals(TskData.TSK_DB_FILES_TYPE_ENUM.FS)) {
				results.add((FsContent) f);
			}


		}
		return results;
	}

	/**
	 * Process a read-only query on the tsk database, any table Can be used to
	 * e.g. to find files of a given criteria. resultSetToFsContents() will
	 * convert the results to useful objects. MUST CALL closeRunQuery() when
	 * done
	 *
	 * @param query the given string query to run
	 * @return	the rs from running the query. Caller MUST CALL
     * closeRunQuery(rs) as soon as possible, when done with retrieving
     * data from the rs
	 * @throws SQLException if error occurred during the query
	 * @deprecated use specific datamodel methods that encapsulate SQL layer
	 */
	@Deprecated
	public ResultSet runQuery(String query) throws SQLException {
		acquireSharedLock();
		try {
			CaseDbConnection connection = connections.getConnection();			
			return connection.executeQuery(connection.createStatement(), query);
		} finally {
			//TODO unlock should be done in closeRunQuery()
			//but currently not all code calls closeRunQuery - need to fix this
			releaseSharedLock();
		}
	}

	/**
	 * Closes ResultSet and its Statement previously retrieved from runQuery()
	 *
	 * @param resultSet with its Statement to close
	 * @throws SQLException of closing the query results failed
	 * @deprecated use specific datamodel methods that encapsulate SQL layer
	 */
	@Deprecated
	public void closeRunQuery(ResultSet resultSet) throws SQLException {
		final Statement statement = resultSet.getStatement();
		resultSet.close();
		if (statement != null) {
			statement.close();
		}
	}

	@Override
	public void finalize() throws Throwable {
		try {
			close();
		} finally {
			super.finalize();
		}
	}

	/**
	 * Call to free resources when done with instance.
	 */
	public void close() {
		System.err.println(this.hashCode() + " closed");
		System.err.flush();
		
		fileSystemIdMap.clear();
		
		SleuthkitCase.acquireExclusiveLock();
		try {
			if (this.caseHandle != null) {
				this.caseHandle.free();
				this.caseHandle = null;


			}
		} catch (TskCoreException ex) {
			logger.log(Level.WARNING,
					"Error freeing case handle.", ex);
		} finally {
			SleuthkitCase.releaseExclusiveLock();
		}
	}

	/**
	 * Make a duplicate / backup copy of the current case database Makes a new
 copy only, and continues to use the current connection
	 *
	 * @param newDBPath path to the copy to be created. File will be overwritten
	 * if it exists
	 * @throws IOException if copying fails
	 */
	public void copyCaseDB(String newDBPath) throws IOException {
		InputStream in = null;
		OutputStream out = null;
		SleuthkitCase.acquireSharedLock();
		try {
			InputStream inFile = new FileInputStream(dbPath);
			in = new BufferedInputStream(inFile);
			OutputStream outFile = new FileOutputStream(newDBPath);
			out = new BufferedOutputStream(outFile);
			int readBytes = 0;
			while ((readBytes = in.read()) != -1) {
				out.write(readBytes);
			}
		} finally {
			try {
				if (in != null) {
					in.close();
				}
				if (out != null) {
					out.flush();
					out.close();


				}
			} catch (IOException e) {
				logger.log(Level.WARNING, "Could not close streams after db copy", e);
			}
			SleuthkitCase.releaseSharedLock();
		}
	}

	/**
	 * Store the known status for the FsContent in the database Note: will not
	 * update status if content is already 'Known Bad'
	 *
	 * @param	file	The AbstractFile object
	 * @param	fileKnown	The object's known status
	 * @return	true if the known status was updated, false otherwise
	 * @throws TskCoreException thrown if a critical error occurred within tsk
	 * core
	 */
	public boolean setKnown(AbstractFile file, FileKnown fileKnown) throws TskCoreException {
		long id = file.getId();
		FileKnown currentKnown = file.getKnown();
		if (currentKnown.compareTo(fileKnown) > 0) {
			return false;
		}
		SleuthkitCase.acquireSharedLock();
		try {
			CaseDbConnection connection = connections.getConnection();
			connection.executeUpdate("UPDATE tsk_files "
					+ "SET known='" + fileKnown.getFileKnownValue() + "' "
					+ "WHERE obj_id=" + id);
			file.setKnown(fileKnown);
		} catch (SQLException ex) {
			throw new TskCoreException("Error setting Known status.", ex);
		} finally {
			SleuthkitCase.releaseSharedLock();
		}
		return true;
	}

	/**
	 * Store the md5Hash for the file in the database
	 *
	 * @param	file	The file object
	 * @param	md5Hash	The object's md5Hash
	 * @throws TskCoreException thrown if a critical error occurred within tsk
	 * core
	 */
	void setMd5Hash(AbstractFile file, String md5Hash) throws TskCoreException {
		long id = file.getId();
		SleuthkitCase.acquireExclusiveLock();
		try {
			CaseDbConnection connection = connections.getConnection();
			PreparedStatement statement = connection.getPreparedStatement(CaseDbConnection.PREPARED_STATEMENT.UPDATE_FILE_MD5);	
			statement.clearParameters();
			statement.setString(1, md5Hash);
			statement.setLong(2, id);
			connection.executeUpdate(statement);
			//update the object itself
			file.setMd5Hash(md5Hash);
		} catch (SQLException ex) {
			throw new TskCoreException("Error setting MD5 hash.", ex);
		} finally {
			SleuthkitCase.releaseExclusiveLock();
		}
	}

	/**
	 * Return the number of objects in the database of a given file type.
	 *
	 * @param contentType Type of file to count
	 * @return Number of objects with that type.
	 * @throws TskCoreException thrown if a critical error occurred within tsk
	 * core
	 */
	public int countFsContentType(TskData.TSK_FS_META_TYPE_ENUM contentType) throws TskCoreException {
		acquireSharedLock();
		Statement s = null;				
		ResultSet rs = null;						 			
		try {
			CaseDbConnection connection = connections.getConnection();			
			s = connection.createStatement();
			Short contentShort = contentType.getValue();
			rs = connection.executeQuery(s, "SELECT COUNT(*) FROM tsk_files WHERE meta_type = '" + contentShort.toString() + "'");
			int count = 0;
			if (rs.next()) {
				count = rs.getInt(1);
			}
			return count;
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting number of objects.", ex);
		} finally {
			closeResultSet(rs);			
			closeStatement(s);
			releaseSharedLock();
		}
	}

	/**
	 * Escape the single quotes in the given string so they can be added to the
     * SQL connection
	 *
	 * @param text
	 * @return text the escaped version
	 */
	private static String escapeForBlackboard(String text) {
		if (text != null) {
			text = text.replaceAll("'", "''");
		}
		return text;
	}

	/**
	 * Find all the files with the given MD5 hash.
	 *
	 * @param md5Hash hash value to match files with
	 * @return List of AbstractFile with the given hash
	 */
	public List<AbstractFile> findFilesByMd5(String md5Hash) {
		acquireSharedLock();
		Statement s = null;				
		ResultSet rs = null;			
		try {
			CaseDbConnection connection = connections.getConnection();			
			s = connection.createStatement();
			rs = connection.executeQuery(s, "SELECT * FROM tsk_files WHERE "
					+ " md5 = '" + md5Hash + "' "
					+ "AND size > 0");
			return resultSetToAbstractFiles(rs);
		} catch (SQLException ex) {
			logger.log(Level.WARNING, "Error querying database.", ex);
		} finally {
			closeResultSet(rs);			
			closeStatement(s);
			releaseSharedLock();
		}
		return Collections.<AbstractFile>emptyList();
	}

	/**
	 * Query all the files to verify if they have an MD5 hash associated with
	 * them.
	 *
	 * @return true if all files have an MD5 hash
	 */
	public boolean allFilesMd5Hashed() {
		acquireSharedLock();
		boolean allFilesAreHashed = false;
		Statement s = null;				
		ResultSet rs = null;			
		try {
			CaseDbConnection connection = connections.getConnection();			
			s = connection.createStatement();
			rs = connection.executeQuery(s, "SELECT COUNT(*) FROM tsk_files "
					+ "WHERE dir_type = '" + TskData.TSK_FS_NAME_TYPE_ENUM.REG.getValue() + "' "
					+ "AND md5 IS NULL "
					+ "AND size > '0'");
			if (rs.next() && rs.getInt(1) == 0) {
				allFilesAreHashed = true;
			}
		} catch (SQLException ex) {
			logger.log(Level.WARNING, "Failed to query whether all files have MD5 hashes", ex);
		} finally {
			closeResultSet(rs);			
			closeStatement(s);
			releaseSharedLock();
		}
		return allFilesAreHashed;
	}

	/**
	 * Query all the files and counts how many have an MD5 hash.
	 *
	 * @return the number of files with an MD5 hash
	 */
	public int countFilesMd5Hashed() {
		int count = 0;
		acquireSharedLock();
		Statement s = null;				
		ResultSet rs = null;			
		try {
			CaseDbConnection connection = connections.getConnection();			
			s = connection.createStatement();
			rs = connection.executeQuery(s, "SELECT COUNT(*) FROM tsk_files "
					+ "WHERE md5 IS NOT NULL "
					+ "AND size > '0'");
			if (rs.next()) {
				count = rs.getInt(1);
			}
		} catch (SQLException ex) {
			logger.log(Level.WARNING, "Failed to query for all the files.", ex);
		} finally {
			closeResultSet(rs);			
			closeStatement(s);
			releaseSharedLock();
		}
		return count;
	}

	/**
	 * This is a temporary workaround to avoid an API change.
	 *
	 * @deprecated
	 */
	@Deprecated
	public interface ErrorObserver {

		void receiveError(String context, String errorMessage);
	}

	/**
	 * This is a temporary workaround to avoid an API change.
	 *
	 * @deprecated
	 * @param observer The observer to add.
	 */
	@Deprecated
	public void addErrorObserver(ErrorObserver observer) {
		errorObservers.add(observer);
	}

	/**
	 * This is a temporary workaround to avoid an API change.
	 *
	 * @deprecated
	 * @param observer The observer to remove.
	 */
	@Deprecated
	public void removerErrorObserver(ErrorObserver observer) {
		int i = errorObservers.indexOf(observer);
		if (i >= 0) {
			errorObservers.remove(i);
		}
	}

	/**
	 * This is a temporary workaround to avoid an API change.
	 *
	 * @deprecated
	 * @param context The context in which the error occurred.
	 * @param errorMessage A description of the error that occurred.
	 */
	@Deprecated
	public void submitError(String context, String errorMessage) {
		for (ErrorObserver observer : errorObservers) {
			observer.receiveError(context, errorMessage);
		}
	}
	
	/**
	 * Selects all of the rows from the tag_names table in the case database.
	 * @return A list, possibly empty, of TagName data transfer objects (DTOs) for the rows.
	 * @throws TskCoreException 
	 */
	public List<TagName> getAllTagNames() throws TskCoreException {
		acquireSharedLock();
		ResultSet resultSet = null;
		try {
			// SELECT * FROM tag_names
			CaseDbConnection connection = connections.getConnection();
			PreparedStatement statement = connection.getPreparedStatement(CaseDbConnection.PREPARED_STATEMENT.SELECT_TAG_NAMES);			
			resultSet = connection.executeQuery(statement);
			ArrayList<TagName> tagNames = new ArrayList<TagName>();
			while(resultSet.next()) {
				tagNames.add(new TagName(resultSet.getLong("tag_name_id"), resultSet.getString("display_name"), resultSet.getString("description"), TagName.HTML_COLOR.getColorByName(resultSet.getString("color"))));
			}
			return tagNames;
		}
		catch(SQLException ex) {
			throw new TskCoreException("Error selecting rows from tag_names table", ex);
		}
		finally {
			closeResultSet(resultSet);
			releaseSharedLock();
		}
	}
	
	/**
	 * Selects all of the rows from the tag_names table in the case database for 
	 * which there is at least one matching row in the content_tags or 
	 * blackboard_artifact_tags tables.
	 * 
	 * @return A list, possibly empty, of TagName data transfer objects (DTOs) for the rows.
	 * @throws TskCoreException 
	 */
	public List<TagName> getTagNamesInUse() throws TskCoreException {
		acquireSharedLock();
		ResultSet resultSet = null;
		try {
			// SELECT * FROM tag_names WHERE tag_name_id IN (SELECT tag_name_id from content_tags UNION SELECT tag_name_id FROM blackboard_artifact_tags)
			CaseDbConnection connection = connections.getConnection();
			PreparedStatement statement = connection.getPreparedStatement(CaseDbConnection.PREPARED_STATEMENT.SELECT_TAG_NAMES_IN_USE);			
			resultSet = connection.executeQuery(statement);			
			ArrayList<TagName> tagNames = new ArrayList<TagName>();
			while(resultSet.next()) {
				tagNames.add(new TagName(resultSet.getLong("tag_name_id"), resultSet.getString("display_name"), resultSet.getString("description"), TagName.HTML_COLOR.getColorByName(resultSet.getString("color"))));
			}
			return tagNames;
		}
		catch(SQLException ex) {
			throw new TskCoreException("Error selecting rows from tag_names table", ex);
		}
		finally {
			closeResultSet(resultSet);
			releaseSharedLock();
		}
	}
	
	/**
	 * Inserts row into the tags_names table in the case database.
	 * 
     * @param displayName The display name for the new tag name.
     * @param description The description for the new tag name.
     * @param color The HTML color to associate with the new tag name.
	 * @return A TagName data transfer object (DTO) for the new row.
	 * @throws TskCoreException 
	 */
	public TagName addTagName(String displayName, String description, TagName.HTML_COLOR color) throws TskCoreException {
		acquireExclusiveLock();		
		ResultSet resultSet = null;
		try {
			// INSERT INTO tag_names (display_name, description, color) VALUES (?, ?, ?)			
			CaseDbConnection connection = connections.getConnection();
			PreparedStatement statement = connection.getPreparedStatement(CaseDbConnection.PREPARED_STATEMENT.INSERT_TAG_NAME);			
			statement.clearParameters(); 			
			statement.setString(1, displayName);
			statement.setString(2, description);
			statement.setString(3, color.getName());
			connection.executeUpdate(statement);

			// SELECT MAX(id) FROM tag_names
			statement = connection.getPreparedStatement(CaseDbConnection.PREPARED_STATEMENT.SELECT_MAX_ID_FROM_TAG_NAMES);			
			resultSet = connection.executeQuery(statement);			
			return new TagName(resultSet.getLong(1), displayName, description, color);			
		}
		catch (SQLException ex) {
			throw new TskCoreException("Error adding row for " + displayName + " tag name to tag_names table", ex);
		}
		finally {
			closeResultSet(resultSet);
			releaseExclusiveLock();
		}
	}
	
	/**
	 * Inserts a row into the content_tags table in the case database.
	 * 
     * @param content The content to tag.
     * @param tagName The name to use for the tag.
     * @param comment A comment to store with the tag.
     * @param beginByteOffset Designates the beginning of a tagged section. 
     * @param endByteOffset Designates the end of a tagged section.
	 * @return A ContentTag data transfer object (DTO) for the new row.
	 * @throws TskCoreException 
	 */
	public ContentTag addContentTag(Content content, TagName tagName, String comment, long beginByteOffset, long endByteOffset) throws TskCoreException {
		acquireExclusiveLock();		
		ResultSet resultSet = null;
		try {			
			// INSERT INTO content_tags (obj_id, tag_name_id, comment, begin_byte_offset, end_byte_offset) VALUES (?, ?, ?, ?, ?)
			CaseDbConnection connection = connections.getConnection();
			PreparedStatement statement = connection.getPreparedStatement(CaseDbConnection.PREPARED_STATEMENT.INSERT_CONTENT_TAG);			
			statement.clearParameters(); 			
			statement.setLong(1, content.getId());
			statement.setLong(2, tagName.getId());
			statement.setString(3, comment);
			statement.setLong(4, beginByteOffset);
			statement.setLong(5, endByteOffset);
			connection.executeUpdate(statement);

			// SELECT MAX(tag_id) FROM content_tags
			statement = connection.getPreparedStatement(CaseDbConnection.PREPARED_STATEMENT.SELECT_MAX_ID_FROM_CONTENT_TAGS);			
			resultSet = connection.executeQuery(statement);			
			return new ContentTag(resultSet.getLong(1), content, tagName, comment, beginByteOffset, endByteOffset);
		}
		catch (SQLException ex) {
			throw new TskCoreException("Error adding row to content_tags table (obj_id = " +content.getId() + ", tag_name_id = " + tagName.getId() + ")", ex);
		}
		finally {
			closeResultSet(resultSet);
			releaseExclusiveLock();
		}	
	}
	
	/*
	 * Deletes a row from the content_tags table in the case database.
	 * @param tag A ContentTag data transfer object (DTO) for the row to delete.
	 * @throws TskCoreException 
	 */
	public void deleteContentTag(ContentTag tag) throws TskCoreException {
		acquireExclusiveLock();		
		try {			
			// DELETE FROM content_tags WHERE tag_id = ?		
			CaseDbConnection connection = connections.getConnection();
			PreparedStatement statement = connection.getPreparedStatement(CaseDbConnection.PREPARED_STATEMENT.DELETE_CONTENT_TAG);			
			statement.clearParameters(); 			
			statement.setLong(1, tag.getId());
			connection.executeUpdate(statement);
		}
		catch (SQLException ex) {
			throw new TskCoreException("Error deleting row from content_tags table (id = " + tag.getId() + ")", ex);
		}
		finally {
			releaseExclusiveLock();
		}	
	}

	/**
	 * Selects all of the rows from the content_tags table in the case database.
	 * @return A list, possibly empty, of ContentTag data transfer objects (DTOs) for the rows.
	 * @throws TskCoreException 
	 */
	public List<ContentTag> getAllContentTags() throws TskCoreException {
		acquireSharedLock();		
		ResultSet resultSet = null;
		try {
			// SELECT * FROM content_tags INNER JOIN tag_names ON content_tags.tag_name_id = tag_names.tag_name_id
			CaseDbConnection connection = connections.getConnection();
			PreparedStatement statement = connection.getPreparedStatement(CaseDbConnection.PREPARED_STATEMENT.SELECT_CONTENT_TAGS);			
			resultSet = connection.executeQuery(statement);			
			ArrayList<ContentTag> tags = new ArrayList<ContentTag>();			
			while (resultSet.next()) {
				TagName tagName = new TagName(resultSet.getLong(2), resultSet.getString("display_name"), resultSet.getString("description"), TagName.HTML_COLOR.getColorByName(resultSet.getString("color"))); 
				Content content = getContentById(resultSet.getLong("obj_id"));
				tags.add(new ContentTag(resultSet.getLong("tag_id"), content, tagName, resultSet.getString("comment"), resultSet.getLong("begin_byte_offset"), resultSet.getLong("end_byte_offset"))); 
			} 
			return tags;
		}
		catch (SQLException ex) {
			throw new TskCoreException("Error selecting rows from content_tags table", ex);
		}
		finally {
			closeResultSet(resultSet);
			releaseSharedLock();
		}					
	}
		
	/**
	 * Gets a count of the rows in the content_tags table in the case database 
	 * with a specified foreign key into the tag_names table.
	 * 
	 * @param tagName A data transfer object (DTO) for the tag name to match.
	 * @return The count, possibly zero.
	 * @throws TskCoreException 
	 */
	public long getContentTagsCountByTagName(TagName tagName) throws TskCoreException {
		if (tagName.getId() == Tag.ID_NOT_SET) {
			throw new TskCoreException("TagName object is invalid, id not set");
		}		
		acquireSharedLock();
		ResultSet resultSet = null;
		try {
			// SELECT COUNT(*) FROM content_tags WHERE tag_name_id = ?
			CaseDbConnection connection = connections.getConnection();
			PreparedStatement statement = connection.getPreparedStatement(CaseDbConnection.PREPARED_STATEMENT.COUNT_CONTENT_TAGS_BY_TAG_NAME);			
			statement.clearParameters();
			statement.setLong(1, tagName.getId());
			resultSet = connection.executeQuery(statement);			
			if (resultSet.next()) {
				return resultSet.getLong(1);
			} 
			else {
				throw new TskCoreException("Error getting content_tags row count for tag name (tag_name_id = " + tagName.getId() + ")");
			}
		}
		catch (SQLException ex) {
			throw new TskCoreException("Error getting content_tags row count for tag name (tag_name_id = " + tagName.getId() + ")", ex);
		}
		finally {
			closeResultSet(resultSet);
			releaseSharedLock();
		}			
	}
		
	/**
	 * Selects the rows in the content_tags table in the case database with a 
	 * specified foreign key into the tag_names table.
	 * 
	 * @param tagName A data transfer object (DTO) for the tag name to match.
	 * @return A list, possibly empty, of ContentTag data transfer objects (DTOs) for the rows.
	 * @throws TskCoreException 
	 */
	public List<ContentTag> getContentTagsByTagName(TagName tagName) throws TskCoreException {
		if (tagName.getId() == Tag.ID_NOT_SET) {
			throw new TskCoreException("TagName object is invalid, id not set");
		}		
		acquireSharedLock();		
		ResultSet resultSet = null;
		try {
			// SELECT * FROM content_tags WHERE tag_name_id = ?
			CaseDbConnection connection = connections.getConnection();
			PreparedStatement statement = connection.getPreparedStatement(CaseDbConnection.PREPARED_STATEMENT.SELECT_CONTENT_TAGS_BY_TAG_NAME);						
			statement.clearParameters();
			statement.setLong(1, tagName.getId());
			resultSet = connection.executeQuery(statement);			
			ArrayList<ContentTag> tags = new ArrayList<ContentTag>();			
			while(resultSet.next()) {
				ContentTag tag = new ContentTag(resultSet.getLong("tag_id"), getContentById(resultSet.getLong("obj_id")), tagName, resultSet.getString("comment"), resultSet.getLong("begin_byte_offset"), resultSet.getLong("end_byte_offset")); 
				tags.add(tag);				
			}						
			resultSet.close();
			return tags;
		}
		catch (SQLException ex) {
			throw new TskCoreException("Error getting content_tags rows (tag_name_id = " + tagName.getId() + ")", ex);
		}
		finally {
			closeResultSet(resultSet);
			releaseSharedLock();
		}			
	}

	/**
	 * Selects the rows in the content_tags table in the case database with a 
	 * specified foreign key into the tsk_objects table.
	 * 
	 * @param content A data transfer object (DTO) for the content to match.
	 * @return A list, possibly empty, of ContentTag data transfer objects (DTOs) for the rows.
	 * @throws TskCoreException 
	 */
	public List<ContentTag> getContentTagsByContent(Content content) throws TskCoreException {
		acquireSharedLock();		
		ResultSet resultSet = null;
		try {
			// SELECT * FROM content_tags INNER JOIN tag_names ON content_tags.tag_name_id = tag_names.tag_name_id WHERE content_tags.obj_id = ?
			CaseDbConnection connection = connections.getConnection();
			PreparedStatement statement = connection.getPreparedStatement(CaseDbConnection.PREPARED_STATEMENT.SELECT_CONTENT_TAGS_BY_CONTENT);						
			statement.clearParameters();
			statement.setLong(1, content.getId());			
			resultSet = connection.executeQuery(statement);			
			ArrayList<ContentTag> tags = new ArrayList<ContentTag>();			
			while (resultSet.next()) {
				TagName tagName = new TagName(resultSet.getLong(2), resultSet.getString("display_name"), resultSet.getString("description"), TagName.HTML_COLOR.getColorByName(resultSet.getString("color"))); 
				ContentTag tag = new ContentTag(resultSet.getLong("tag_id"), content, tagName, resultSet.getString("comment"), resultSet.getLong("begin_byte_offset"), resultSet.getLong("end_byte_offset")); 
				tags.add(tag);
			} 
			return tags;
		}
		catch (SQLException ex) {
			throw new TskCoreException("Error getting content tags data for content (obj_id = " + content.getId() + ")", ex);
		}
		finally {
			closeResultSet(resultSet);
			releaseSharedLock();
		}					
	}	
		
	/**
	 * Inserts a row into the blackboard_artifact_tags table in the case database.
	 * 
     * @param artifact The blackboard artifact to tag.
     * @param tagName The name to use for the tag.
     * @param comment A comment to store with the tag.
	 * @return A BlackboardArtifactTag data transfer object (DTO) for the new row.
	 * @throws TskCoreException 
	 */
	public BlackboardArtifactTag addBlackboardArtifactTag(BlackboardArtifact artifact, TagName tagName, String comment) throws TskCoreException {
		acquireExclusiveLock();		
		ResultSet resultSet = null;
		try {			
			// INSERT INTO blackboard_artifact_tags (artifact_id, tag_name_id, comment, begin_byte_offset, end_byte_offset) VALUES (?, ?, ?, ?, ?)			
			CaseDbConnection connection = connections.getConnection();
			PreparedStatement statement = connection.getPreparedStatement(CaseDbConnection.PREPARED_STATEMENT.INSERT_ARTIFACT_TAG);						
			statement.clearParameters(); 			
			statement.setLong(1, artifact.getArtifactID());
			statement.setLong(2, tagName.getId());
			statement.setString(3, comment);
			connection.executeUpdate(statement);

			// SELECT MAX(tag_id) FROM blackboard_artifact_tags
			statement = connection.getPreparedStatement(CaseDbConnection.PREPARED_STATEMENT.SELECT_MAX_ID_FROM_ARTIFACT_TAGS);						
			resultSet = connection.executeQuery(statement);			
			return new BlackboardArtifactTag(resultSet.getLong(1), artifact, getContentById(artifact.getObjectID()), tagName, comment);
		}
		catch (SQLException ex) {
			throw new TskCoreException("Error adding row to blackboard_artifact_tags table (obj_id = " + artifact.getArtifactID() + ", tag_name_id = " + tagName.getId() + ")", ex);
		}
		finally {
			closeResultSet(resultSet);
			releaseExclusiveLock();
		}	
	}	

	/*
	 * Deletes a row from the blackboard_artifact_tags table in the case database.
	 * @param tag A BlackboardArtifactTag data transfer object (DTO) representing the row to delete.
	 * @throws TskCoreException 
	 */
	public void deleteBlackboardArtifactTag(BlackboardArtifactTag tag) throws TskCoreException {
		acquireExclusiveLock();		
		try {			
			// DELETE FROM blackboard_artifact_tags WHERE tag_id = ?
			CaseDbConnection connection = connections.getConnection();
			PreparedStatement statement = connection.getPreparedStatement(CaseDbConnection.PREPARED_STATEMENT.DELETE_ARTIFACT_TAG);						
			statement.clearParameters(); 			
			statement.setLong(1, tag.getId());
			connection.executeUpdate(statement);
		}
		catch (SQLException ex) {
			throw new TskCoreException("Error deleting row from blackboard_artifact_tags table (id = " + tag.getId() + ")", ex);
		}
		finally {
			releaseExclusiveLock();
		}	
	}
	
	/**
	 * Selects all of the rows from the blackboard_artifacts_tags table in the case database.
	 * @return A list, possibly empty, of BlackboardArtifactTag data transfer objects (DTOs) for the rows.
	 * @throws TskCoreException 
	 */
	public List<BlackboardArtifactTag> getAllBlackboardArtifactTags() throws TskCoreException {
		acquireSharedLock();		
		ResultSet resultSet = null;
		try {
			// SELECT * FROM blackboard_artifact_tags INNER JOIN tag_names ON blackboard_artifact_tags.tag_name_id = tag_names.tag_name_id
			CaseDbConnection connection = connections.getConnection();
			PreparedStatement statement = connection.getPreparedStatement(CaseDbConnection.PREPARED_STATEMENT.SELECT_ARTIFACT_TAGS);						
			resultSet = connection.executeQuery(statement);			
			ArrayList<BlackboardArtifactTag> tags = new ArrayList<BlackboardArtifactTag>();
			while (resultSet.next()) {
				TagName tagName = new TagName(resultSet.getLong(2), resultSet.getString("display_name"), resultSet.getString("description"), TagName.HTML_COLOR.getColorByName(resultSet.getString("color"))); 
				BlackboardArtifact artifact = getBlackboardArtifact(resultSet.getLong("artifact_id"));
				Content content = getContentById(artifact.getObjectID());
				BlackboardArtifactTag tag = new BlackboardArtifactTag(resultSet.getLong("tag_id"), artifact, content, tagName, resultSet.getString("comment")); 
				tags.add(tag);
			} 
			resultSet.close();
			return tags;
		}
		catch (SQLException ex) {
			throw new TskCoreException("Error selecting rows from blackboard_artifact_tags table", ex);
		}
		finally {
			closeResultSet(resultSet);
			releaseSharedLock();
		}					
	}
			
	/**
	 * Gets a count of the rows in the blackboard_artifact_tags table in the case database 
	 * with a specified foreign key into the tag_names table.
	 * 
	 * @param tagName A data transfer object (DTO) for the tag name to match.
	 * @return The count, possibly zero.
	 * @throws TskCoreException 
	 */
	public long getBlackboardArtifactTagsCountByTagName(TagName tagName) throws TskCoreException {
		if (tagName.getId() == Tag.ID_NOT_SET) {
			throw new TskCoreException("TagName object is invalid, id not set");
		}		
		acquireSharedLock();
		ResultSet resultSet = null;
		try {
			// SELECT COUNT(*) FROM blackboard_artifact_tags WHERE tag_name_id = ?
			CaseDbConnection connection = connections.getConnection();
			PreparedStatement statement = connection.getPreparedStatement(CaseDbConnection.PREPARED_STATEMENT.COUNT_ARTIFACTS_BY_TAG_NAME);						
			statement.clearParameters();
			statement.setLong(1, tagName.getId());
			resultSet = connection.executeQuery(statement);			
			if (resultSet.next()) {
				return resultSet.getLong(1);
			} 
			else {
				throw new TskCoreException("Error getting blackboard_artifact_tags row count for tag name (tag_name_id = " + tagName.getId() + ")");
			}
		}
		catch (SQLException ex) {
			throw new TskCoreException("Error getting blackboard artifact_content_tags row count for tag name (tag_name_id = " + tagName.getId() + ")", ex);
		}
		finally {
			closeResultSet(resultSet);
			releaseSharedLock();
		}			
	}
		
	/**
	 * Selects the rows in the blackboard_artifacts_tags table in the case database with a 
	 * specified foreign key into the tag_names table.
	 * 
	 * @param tagName A data transfer object (DTO) for the tag name to match.
	 * @return A list, possibly empty, of BlackboardArtifactTag data transfer objects (DTOs) for the rows.
	 * @throws TskCoreException 
	 */
	public List<BlackboardArtifactTag> getBlackboardArtifactTagsByTagName(TagName tagName) throws TskCoreException {
		if (tagName.getId() == Tag.ID_NOT_SET) {
			throw new TskCoreException("TagName object is invalid, id not set");
		}		
		acquireSharedLock();
		ResultSet resultSet = null;
		try {
			// SELECT * FROM blackboard_artifact_tags WHERE tag_name_id = ?
			CaseDbConnection connection = connections.getConnection();
			PreparedStatement statement = connection.getPreparedStatement(CaseDbConnection.PREPARED_STATEMENT.SELECT_ARTIFACT_TAGS_BY_TAG_NAME);						
			statement.clearParameters();
			statement.setLong(1, tagName.getId());
			resultSet = connection.executeQuery(statement);			
			ArrayList<BlackboardArtifactTag> tags = new ArrayList<BlackboardArtifactTag>();			
			while(resultSet.next()) {
				BlackboardArtifact artifact = getBlackboardArtifact(resultSet.getLong("artifact_id"));
				Content content = getContentById(artifact.getObjectID());
				BlackboardArtifactTag tag = new BlackboardArtifactTag(resultSet.getLong("tag_id"), artifact, content, tagName, resultSet.getString("comment")); 
				tags.add(tag);
			}
			return tags;
		}
		catch (SQLException ex) {
			throw new TskCoreException("Error getting blackboard artifact tags data (tag_name_id = " + tagName.getId() + ")", ex);
		}
		finally {
			closeResultSet(resultSet);
			releaseSharedLock();
		}			
	}	
	
	/**
	 * Selects the rows in the blackboard_artifacts_tags table in the case database with a 
	 * specified foreign key into the blackboard_artifacts table.
	 * 
	 * @param artifact A data transfer object (DTO) for the artifact to match.
	 * @return A list, possibly empty, of BlackboardArtifactTag data transfer objects (DTOs) for the rows.
	 * @throws TskCoreException 
	 */
	public List<BlackboardArtifactTag> getBlackboardArtifactTagsByArtifact(BlackboardArtifact artifact) throws TskCoreException {
		acquireSharedLock();		
		ResultSet resultSet = null;
		try {
			// SELECT * FROM blackboard_artifact_tags INNER JOIN tag_names ON blackboard_artifact_tags.tag_name_id = tag_names.tag_name_id WHERE blackboard_artifact_tags.artifact_id = ?			
			CaseDbConnection connection = connections.getConnection();
			PreparedStatement statement = connection.getPreparedStatement(CaseDbConnection.PREPARED_STATEMENT.SELECT_ARTIFACT_TAGS_BY_ARTIFACT);						
			statement.clearParameters();
			statement.setLong(1, artifact.getArtifactID());
			resultSet = connection.executeQuery(statement);			
			ArrayList<BlackboardArtifactTag> tags = new ArrayList<BlackboardArtifactTag>();			
			while(resultSet.next()) {
				TagName tagName = new TagName(resultSet.getLong(2), resultSet.getString("display_name"), resultSet.getString("description"), TagName.HTML_COLOR.getColorByName(resultSet.getString("color"))); 
				Content content = getContentById(artifact.getObjectID());
				BlackboardArtifactTag tag = new BlackboardArtifactTag(resultSet.getLong("tag_id"), artifact, content, tagName, resultSet.getString("comment")); 
				tags.add(tag);
			}
			return tags;
		}
		catch (SQLException ex) {
			throw new TskCoreException("Error getting blackboard artifact tags data (artifact_id = " + artifact.getArtifactID() + ")", ex);
		}
		finally {
			closeResultSet(resultSet);
			releaseSharedLock();
		}					
	}	

	/**
	 * Inserts a row into the reports table in the case database.
	 * 
	 * @param localPath The path of the report file, must be in the database directory (case directory in Autopsy) or one of its subdirectories.
	 * @param sourceModuleName The name of the module that created the report.
	 * @param reportName The report name, may be empty.
	 * @return A Report data transfer object (DTO) for the new row.
	 * @throws TskCoreException 
	 */
	public Report addReport(String localPath, String sourceModuleName, String reportName) throws TskCoreException {
		acquireExclusiveLock();
		ResultSet resultSet = null;
		try {
			// Make sure the local path of the report is in the database directory
			// or one of its subdirectories.
			String relativePath = "";
			try {
				Path path = Paths.get(localPath);
				Path pathBase = Paths.get(getDbDirPath());
				relativePath = pathBase.relativize(path).toString();
			} catch (IllegalArgumentException ex) {
				String errorMessage = String.format("Local path %s not in the database directory or one of its subdirectories", localPath);
				throw new TskCoreException(errorMessage, ex);
			}
						
			// Figure out the create time of the report.
			long createTime = 0;			
			try {
				java.io.File tempFile = new java.io.File(localPath);
                // Convert to UNIX epoch (seconds, not milliseconds).
				createTime = tempFile.lastModified() / 1000;
			} catch(Exception ex) {
				throw new TskCoreException("Could not get create time for report at " + localPath, ex);
			}
									
			// INSERT INTO reports (path, crtime, src_module_name, display_name) VALUES (?, ?, ?, ?)			
			CaseDbConnection connection = connections.getConnection();
			PreparedStatement statement = connection.getPreparedStatement(CaseDbConnection.PREPARED_STATEMENT.INSERT_REPORT);			
			statement.clearParameters(); 			
			statement.setString(1, relativePath);			
			statement.setLong(2, createTime);
			statement.setString(3, sourceModuleName);			
			statement.setString(4, reportName);			
			connection.executeUpdate(statement);

			// SELECT MAX(report_id) FROM reports
			statement = connection.getPreparedStatement(CaseDbConnection.PREPARED_STATEMENT.SELECT_MAX_ID_FROM_REPORTS);
			resultSet = connection.executeQuery(statement);			
			return new Report(resultSet.getLong(1), localPath, createTime, sourceModuleName, reportName);			
		}
		catch (SQLException ex) {
			throw new TskCoreException("Error adding report " + localPath + " to reports table", ex);
		}
		finally {
			closeResultSet(resultSet);
			releaseExclusiveLock();
		}
    }
	
	/**
	 * Selects all of the rows from the reports table in the case database.
	 * 
	 * @return A list, possibly empty, of Report data transfer objects (DTOs) for the rows.
	 * @throws TskCoreException 
	 */
	public List<Report> getAllReports() throws TskCoreException {
		acquireSharedLock();		
		ResultSet resultSet = null;
		try {
			CaseDbConnection connection = connections.getConnection();
			PreparedStatement statement = connection.getPreparedStatement(CaseDbConnection.PREPARED_STATEMENT.SELECT_REPORTS);						
			resultSet = connection.executeQuery(statement);			
			ArrayList<Report> reports = new ArrayList<Report>();			
			while (resultSet.next()) {
				reports.add(new Report(resultSet.getLong("report_id"), 
                    getDbDirPath() + java.io.File.separator + resultSet.getString("path"), 
					resultSet.getLong("crtime"), 
					resultSet.getString("src_module_name"),
			        resultSet.getString("report_name"))); 
			} 
			return reports;
		}
		catch (SQLException ex) {
			throw new TskCoreException("Error querying reports table", ex);
		}
		finally {
			closeResultSet(resultSet);
			releaseSharedLock();
		}					
	}	

     /**
     * Returns case database schema version number. 	
     *  
     * @return and integer of the schema version number. 
     */
	public int getSchemaVersion(){
		return this.versionNumber;
	}

	private static void closeResultSet(ResultSet resultSet) {
		if (resultSet != null) {
			try {
				resultSet.close();
			} catch (SQLException ex) {
				logger.log(Level.SEVERE, "Error closing ResultSet", ex);
			}
		}
	}
			
	private static void closeStatement(Statement statement) {
		if (statement != null) {
			try {
				statement.close();
			} catch (SQLException ex) {
				logger.log(Level.SEVERE, "Error closing Statement", ex);
			}
		}
	}
					
	/**
	 * Provides thread confinement for connections to the underlying case 
	 * database. ThreadLocal<T> releases the object reference for a thread for 
	 * garbage collection when thread goes away. The Connection objects will be 
	 * closed when they are garbage collected. This covers both short-lived 
	 * threads like those that do asynchronous child node creation and 
	 * long-lived threads such as the EDT and ingest threads.
	 */
	private final class ConnectionPerThreadDispenser extends ThreadLocal<CaseDbConnection> {	

		CaseDbConnection getConnectionWithoutPreparedStatements() throws SQLException {
			CaseDbConnection connection = get();
			if (null == connection) {
				throw new SQLException("Case database connection for current thread is null");
			}
			return connection;
		}
									
		CaseDbConnection getConnection() throws SQLException {
			CaseDbConnection connection = getConnectionWithoutPreparedStatements();
			if (!connection.statementsArePrepared()) {
				connection.prepareStatements();
			}
			return connection;
		}

		@Override
		public CaseDbConnection initialValue() {
			return new CaseDbConnection(dbPath);
		}
	}
		
	/**
	 * Encapsulates a connection to the underlying case database and a set of 
	 * prepared statements.
	 */
	private static final class CaseDbConnection {
		
		private Connection connection;
		enum PREPARED_STATEMENT {
						
			SELECT_ATTRIBUTES_OF_ARTIFACT("SELECT artifact_id, source, context, attribute_type_id, value_type, "
					+ "value_byte, value_text, value_int32, value_int64, value_double "
					+ "FROM blackboard_attributes WHERE artifact_id = ?"),
			SELECT_ARTIFACT_BY_ID("SELECT obj_id, artifact_type_id FROM blackboard_artifacts WHERE artifact_id = ?"),
			SELECT_ARTIFACTS_BY_TYPE("SELECT artifact_id, obj_id FROM blackboard_artifacts "
					+ "WHERE artifact_type_id = ?"),
			COUNT_ARTIFACTS_OF_TYPE("SELECT COUNT(*) FROM blackboard_artifacts WHERE artifact_type_id = ?"),
			COUNT_ARTIFACTS_FROM_SOURCE("SELECT COUNT(*) FROM blackboard_artifacts WHERE obj_id = ?"),
			SELECT_ARTIFACTS_BY_SOURCE_AND_TYPE("SELECT artifact_id FROM blackboard_artifacts WHERE obj_id = ? AND artifact_type_id = ?"),
			COUNT_ARTIFACTS_BY_SOURCE_AND_TYPE("SELECT COUNT(*) FROM blackboard_artifacts WHERE obj_id = ? AND artifact_type_id = ?"),
			SELECT_FILES_BY_PARENT("SELECT tsk_files.* " 
					+ "FROM tsk_objects INNER JOIN tsk_files "
					+ "ON tsk_objects.obj_id=tsk_files.obj_id " 
					+ "WHERE (tsk_objects.par_obj_id = ? ) " 
				    + "ORDER BY tsk_files.dir_type, tsk_files.name COLLATE NOCASE"),
			SELECT_FILES_BY_PARENT_AND_TYPE("SELECT tsk_files.* "
					+ "FROM tsk_objects INNER JOIN tsk_files "
					+ "ON tsk_objects.obj_id=tsk_files.obj_id "
					+ "WHERE (tsk_objects.par_obj_id = ? AND tsk_files.type = ? ) "
					+ "ORDER BY tsk_files.dir_type, tsk_files.name COLLATE NOCASE"),
			SELECT_FILE_IDS_BY_PARENT("SELECT tsk_files.obj_id FROM tsk_objects INNER JOIN tsk_files "
					+ "ON tsk_objects.obj_id=tsk_files.obj_id WHERE (tsk_objects.par_obj_id = ?)"),
			SELECT_FILE_IDS_BY_PARENT_AND_TYPE("SELECT tsk_files.obj_id "
					+ "FROM tsk_objects INNER JOIN tsk_files "
					+ "ON tsk_objects.obj_id=tsk_files.obj_id "
					+ "WHERE (tsk_objects.par_obj_id = ? "
					+ "AND tsk_files.type = ? )"),
			SELECT_FILE_BY_ID("SELECT * FROM tsk_files WHERE obj_id = ? LIMIT 1"),
			INSERT_ARTIFACT("INSERT INTO blackboard_artifacts (artifact_id, obj_id, artifact_type_id) "
					+ "VALUES (NULL, ?, ?)"),
			SELECT_MAX_ARTIFACT_ID_BY_SOURCE_AND_TYPE("SELECT MAX(artifact_id) from blackboard_artifacts "
					+ "WHERE obj_id = ? AND + artifact_type_id = ?"),
			INSERT_STRING_ATTRIBUTE("INSERT INTO blackboard_attributes (artifact_id, source, context, attribute_type_id, value_type, value_text) "
					+ "VALUES (?,?,?,?,?,?)"),
			INSERT_BYTE_ATTRIBUTE("INSERT INTO blackboard_attributes (artifact_id, source, context, attribute_type_id, value_type, value_byte) "
					+ "VALUES (?,?,?,?,?,?)"),
			INSERT_INT_ATTRIBUTE("INSERT INTO blackboard_attributes (artifact_id, source, context, attribute_type_id, value_type, value_int32) "
					+ "VALUES (?,?,?,?,?,?)"),
			INSERT_LONG_ATTRIBUTE("INSERT INTO blackboard_attributes (artifact_id, source, context, attribute_type_id, value_type, value_int64) "
					+ "VALUES (?,?,?,?,?,?)"),
			INSERT_DOUBLE_ATTRIBUTE("INSERT INTO blackboard_attributes (artifact_id, source, context, attribute_type_id, value_type, value_double) "
					+ "VALUES (?,?,?,?,?,?)"),
			SELECT_FILES_BY_FILE_SYSTEM_AND_NAME("SELECT * FROM tsk_files WHERE LOWER(name) LIKE ? and LOWER(name) NOT LIKE '%journal%' AND fs_obj_id = ?"),
			SELECT_FILES_BY_FILE_SYSTEM_AND_PATH("SELECT * FROM tsk_files WHERE LOWER(name) LIKE ? AND LOWER(name) NOT LIKE '%journal%' AND LOWER(parent_path) LIKE ? AND fs_obj_id = ?"),
			UPDATE_FILE_MD5("UPDATE tsk_files SET md5 = ? WHERE obj_id = ?"),
			SELECT_LOCAL_PATH_FOR_FILE("SELECT path FROM tsk_files_path WHERE obj_id = ?"),
			SELECT_PATH_FOR_FILE("SELECT parent_path FROM tsk_files WHERE obj_id = ?"),
			SELECT_FILE_NAME("SELECT name FROM tsk_files WHERE obj_id = ?"),
			SELECT_DERIVED_FILE("SELECT derived_id, rederive FROM tsk_files_derived WHERE obj_id = ?"),
			SELECT_FILE_DERIVATION_METHOD("SELECT tool_name, tool_version, other FROM tsk_files_derived_method WHERE derived_id = ?"),
			SELECT_MAX_OBJECT_ID("SELECT MAX(obj_id) from tsk_objects"),
			INSERT_OBJECT("INSERT INTO tsk_objects (obj_id, par_obj_id, type) VALUES (?, ?, ?)"),
			INSERT_FILE("INSERT INTO tsk_files (obj_id, fs_obj_id, name, type, has_path, dir_type, meta_type, dir_flags, meta_flags, size, ctime, crtime, atime, mtime, parent_path) "
					+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"),
			INSERT_LAYOUT_FILE("INSERT INTO tsk_file_layout (obj_id, byte_start, byte_len, sequence) "
					+ "VALUES (?, ?, ?, ?)"),
			INSERT_LOCAL_PATH("INSERT INTO tsk_files_path (obj_id, path) VALUES (?, ?)"),
			COUNT_CHILD_OBJECTS_BY_PARENT("SELECT COUNT(obj_id) FROM tsk_objects WHERE par_obj_id = ?"),
			SELECT_FILE_SYSTEM_BY_OBJECT("SELECT fs_obj_id from tsk_files WHERE obj_id=?"),
			SELECT_TAG_NAMES("SELECT * FROM tag_names"),
			SELECT_TAG_NAMES_IN_USE("SELECT * FROM tag_names "
					+ "WHERE tag_name_id IN "
					+ "(SELECT tag_name_id from content_tags UNION SELECT tag_name_id FROM blackboard_artifact_tags)"),
			INSERT_TAG_NAME("INSERT INTO tag_names (display_name, description, color) VALUES (?, ?, ?)"),
			SELECT_MAX_ID_FROM_TAG_NAMES("SELECT MAX(tag_name_id) FROM tag_names"),
			INSERT_CONTENT_TAG("INSERT INTO content_tags (obj_id, tag_name_id, comment, begin_byte_offset, end_byte_offset) VALUES (?, ?, ?, ?, ?)"),
			SELECT_MAX_ID_FROM_CONTENT_TAGS("SELECT MAX(tag_id) FROM content_tags"),
			DELETE_CONTENT_TAG("DELETE FROM content_tags WHERE tag_id = ?"),
			COUNT_CONTENT_TAGS_BY_TAG_NAME("SELECT COUNT(*) FROM content_tags WHERE tag_name_id = ?"),
			SELECT_CONTENT_TAGS("SELECT * FROM content_tags INNER JOIN tag_names ON content_tags.tag_name_id = tag_names.tag_name_id"),
			SELECT_CONTENT_TAGS_BY_TAG_NAME("SELECT * FROM content_tags WHERE tag_name_id = ?"),
			SELECT_CONTENT_TAGS_BY_CONTENT("SELECT * FROM content_tags INNER JOIN tag_names ON content_tags.tag_name_id = tag_names.tag_name_id WHERE content_tags.obj_id = ?"),
			INSERT_ARTIFACT_TAG("INSERT INTO blackboard_artifact_tags (artifact_id, tag_name_id, comment) VALUES (?, ?, ?)"),
			SELECT_MAX_ID_FROM_ARTIFACT_TAGS("SELECT MAX(tag_id) FROM blackboard_artifact_tags"),	
			DELETE_ARTIFACT_TAG("DELETE FROM blackboard_artifact_tags WHERE tag_id = ?"),
			SELECT_ARTIFACT_TAGS("SELECT * FROM blackboard_artifact_tags INNER JOIN tag_names ON blackboard_artifact_tags.tag_name_id = tag_names.tag_name_id"),
			COUNT_ARTIFACTS_BY_TAG_NAME("SELECT COUNT(*) FROM blackboard_artifact_tags WHERE tag_name_id = ?"),
			SELECT_ARTIFACT_TAGS_BY_TAG_NAME("SELECT * FROM blackboard_artifact_tags WHERE tag_name_id = ?"),
			SELECT_ARTIFACT_TAGS_BY_ARTIFACT("SELECT * FROM blackboard_artifact_tags INNER JOIN tag_names ON blackboard_artifact_tags.tag_name_id = tag_names.tag_name_id WHERE blackboard_artifact_tags.artifact_id = ?"),
			SELECT_REPORTS("SELECT * FROM reports"),
			SELECT_MAX_ID_FROM_REPORTS("SELECT MAX(report_id) FROM reports"),
			INSERT_REPORT("INSERT INTO reports (path, crtime, src_module_name, report_name) VALUES (?, ?, ?, ?)");	
			
			private final String sql;

            private PREPARED_STATEMENT(String sql) {
                this.sql = sql;
            }
			
			String getSQL() {
				return sql;
			}
		}
		private final Map<PREPARED_STATEMENT, PreparedStatement> preparedStatements;
		boolean statementsPrepared = false;
				
		CaseDbConnection(String dbPath) {
			preparedStatements = new EnumMap<PREPARED_STATEMENT, PreparedStatement>(PREPARED_STATEMENT.class);
			try {
				connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
			} catch (SQLException ex) {
				// The exception is caught and logged here because this 
				// constructor will be called by an override of 
				// ThreadLocal<T>.initialValue() which cannot throw. Calls to 
				// ConnectionPerThreadDispenser.getConnection() will detect the
				// the null value of connection and throw an SQLException.
				logger.log(Level.SEVERE, "Error setting up case database connection for thread", ex);
			}
		}

		Connection getConnection() {
			return connection;
		}
						
		void prepareStatements() throws SQLException {
			for (PREPARED_STATEMENT statement : PREPARED_STATEMENT.values()) {
				preparedStatements.put(statement, prepareStatement(statement.getSQL()));				
			}																														
			statementsPrepared = true;
		}

		private PreparedStatement prepareStatement(String sqlStatement) throws SQLException {
			PreparedStatement statement = null;
			boolean locked = true;
			while (locked) {
				try {
					statement = connection.prepareStatement(sqlStatement);
					locked = false;
				} catch (SQLException ex) {
					if (ex.getErrorCode() != SQLITE_BUSY_ERROR && ex.getErrorCode() != DATABASE_LOCKED_ERROR) {
						throw ex;
					}
				}
			}
			return statement;
		}
		
		
		boolean statementsArePrepared() {
			return statementsPrepared;
		}
		
		PreparedStatement getPreparedStatement(PREPARED_STATEMENT statementKey) {
			return preparedStatements.get(statementKey);
		}
								
		void closePreparedStatements() {
			for (PreparedStatement statement : this.preparedStatements.values()) {
				try {
					if (statement != null) {
						statement.close();
						statement = null;
					}			
				} 
				catch (SQLException ex) {
					logger.log(Level.WARNING, "Error closing prepared statement", ex);
				}
			}
			statementsPrepared = false;
		}		

		Statement createStatement() throws SQLException {
			Statement statement = null;
			boolean locked = true;
			while (locked) {
				try {
					statement = connection.createStatement();
					locked = false;
				}
				catch (SQLException ex) {
					if (ex.getErrorCode() != SQLITE_BUSY_ERROR && ex.getErrorCode() != DATABASE_LOCKED_ERROR) {
						throw ex;
					}
				}
			}	
			return statement;
		}
				
		void beginTransaction() throws SQLException {
			connection.setAutoCommit(false);
		}
		
		void commitTransaction() throws SQLException {
			try {
				connection.commit();
			} finally {
				connection.setAutoCommit(true);
			}
		}		

		void rollbackTransaction() throws SQLException {
			try {
				connection.rollback();
			} finally {
				connection.setAutoCommit(true);
			}
		}

		private ResultSet executeQuery(Statement statement, String query) throws SQLException {
			ResultSet resultSet = null;
			boolean locked = true;
			while (locked) {
				try {
					resultSet = statement.executeQuery(query);
					locked = false;
				}
				catch (SQLException ex) {
					if (ex.getErrorCode() != SQLITE_BUSY_ERROR && ex.getErrorCode() != DATABASE_LOCKED_ERROR) {
						throw ex;
					}
				}
			}	
			return resultSet;
		}
		
		private ResultSet executeQuery(PreparedStatement statement) throws SQLException {
			ResultSet resultSet = null;
			boolean locked = true;
			while (locked) {
				try {
					resultSet = statement.executeQuery();
					locked = false;
				}
				catch (SQLException ex) {
					if (ex.getErrorCode() != SQLITE_BUSY_ERROR && ex.getErrorCode() != DATABASE_LOCKED_ERROR) {
						throw ex;
					}
				}
			}	
			return resultSet;
		}

		void executeUpdate(String update) throws SQLException {
			Statement statement = null;
			try {
				boolean locked = true;
				while (locked) {
					try {
						statement = connection.createStatement();
						statement.executeUpdate(update);
						locked = false;
					}
					catch (SQLException ex) {
						if (ex.getErrorCode() != SQLITE_BUSY_ERROR && ex.getErrorCode() != DATABASE_LOCKED_ERROR) {
							throw ex;
						}
					}
				}	
			} finally {
				closeStatement(statement);
			}
		}

		void executeUpdate(PreparedStatement statement) throws SQLException {
			boolean locked = true;
			while (locked) {
				try {
					statement.executeUpdate();
					locked = false;
				}
				catch (SQLException ex) {
					if (ex.getErrorCode() != SQLITE_BUSY_ERROR && ex.getErrorCode() != DATABASE_LOCKED_ERROR) {
						throw ex;
					}
				}
			}					
		}
								
		void close() throws SQLException {
			closePreparedStatements();
			connection.close();
		}								
	}	
}
