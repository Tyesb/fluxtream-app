package com.fluxtream.connectors.evernote;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import com.evernote.auth.EvernoteAuth;
import com.evernote.auth.EvernoteService;
import com.evernote.clients.ClientFactory;
import com.evernote.clients.NoteStoreClient;
import com.evernote.edam.error.EDAMErrorCode;
import com.evernote.edam.error.EDAMSystemException;
import com.evernote.edam.error.EDAMUserException;
import com.evernote.edam.notestore.SyncChunk;
import com.evernote.edam.notestore.SyncState;
import com.evernote.edam.type.Data;
import com.evernote.edam.type.Note;
import com.evernote.edam.type.NoteAttributes;
import com.evernote.edam.type.Notebook;
import com.evernote.edam.type.Resource;
import com.evernote.edam.type.ResourceAttributes;
import com.evernote.edam.type.Tag;
import com.evernote.thrift.TException;
import com.fluxtream.aspects.FlxLogger;
import com.fluxtream.connectors.annotations.Updater;
import com.fluxtream.connectors.location.LocationFacet;
import com.fluxtream.connectors.updaters.AbstractUpdater;
import com.fluxtream.connectors.updaters.AuthExpiredException;
import com.fluxtream.connectors.updaters.RateLimitReachedException;
import com.fluxtream.connectors.updaters.UpdateInfo;
import com.fluxtream.services.ApiDataService;
import com.fluxtream.services.JPADaoService;
import com.fluxtream.services.MetadataService;
import com.fluxtream.utils.JPAUtils;
import com.syncthemall.enml4j.ENMLProcessor;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.tika.mime.MimeType;
import org.apache.tika.mime.MimeTypeException;
import org.apache.tika.mime.MimeTypes;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 *
 * @author Candide Kemmler (candide@fluxtream.com)
 */
@Component
@Updater(prettyName = "Evernote", value = 17, objectTypes ={LocationFacet.class, EvernoteNoteFacet.class,
                                                            EvernoteTagFacet.class, EvernoteNotebookFacet.class,
                                                            EvernoteResourceFacet.class})
public class EvernoteUpdater extends AbstractUpdater {

    public static final String MAIN_APPENDIX = "main";
    public static final String RECOGNITION_APPENDIX = "recognition";
    public static final String ALTERNATE_APPENDIX = "alternate";

    FlxLogger logger = FlxLogger.getLogger(EvernoteUpdater.class);

    private static final int MAX_ENTRIES = 200;
    private static final String LAST_UPDATE_COUNT = "evernoteLastUpdateCount";
    private static final String LAST_SYNC_TIME = "evernoteLastSyncTime";

    @Autowired
    JPADaoService jpaDaoService;

    @Autowired
    MetadataService metadataService;

    ENMLProcessor processor = new ENMLProcessor();

    static {
        System.setProperty("javax.xml.stream.XMLInputFactory", "com.ctc.wstx.stax.WstxInputFactory");
        System.setProperty("javax.xml.stream.XMLOutputFactory", "com.ctc.wstx.stax.WstxOutputFactory");
        System.setProperty("javax.xml.stream.XMLEventFactory", "com.ctc.wstx.stax.WstxEventFactory");
    }

    @Override
    protected void updateConnectorDataHistory(final UpdateInfo updateInfo) throws Exception {
        final NoteStoreClient noteStore = getNoteStoreClient(updateInfo);
        performSync(updateInfo, noteStore, true);
    }

    @Override
    protected void updateConnectorData(final UpdateInfo updateInfo) throws Exception {
        final NoteStoreClient noteStore = getNoteStoreClient(updateInfo);
        final SyncState syncState = noteStore.getSyncState();
        final String lastSyncTimeAtt = guestService.getApiKeyAttribute(updateInfo.apiKey, LAST_SYNC_TIME);
        long lastSyncTime = Long.valueOf(lastSyncTimeAtt);
        final String lastUpdateCountAtt = guestService.getApiKeyAttribute(updateInfo.apiKey, LAST_UPDATE_COUNT);
        long lastUpdateCount = Long.valueOf(lastUpdateCountAtt);
        if (syncState.getFullSyncBefore()>lastSyncTime) {
            // according to the edam sync spec, fullSyncBefore is "the cut-off date for old caching clients
            // to perform an incremental (vs. full) synchronization. This value may correspond to the point
            // where historic data (e.g. regarding expunged objects) was wiped from the account, or possibly
            // the time of a serious server issue that would invalidate client USNs"
            // This means that we are may leave items that the user actually deleted in the database, and thus
            // we need to basically do a history update again
            apiDataService.eraseApiData(updateInfo.apiKey);
            guestService.removeApiKeyAttribute(updateInfo.apiKey.getId(), LAST_SYNC_TIME);
            guestService.removeApiKeyAttribute(updateInfo.apiKey.getId(), LAST_UPDATE_COUNT);
            // let's properly log this
            logger.info("FullSync required for evernote connector, apiKeyId=" + updateInfo.apiKey.getId());
            performSync(updateInfo, noteStore, true);
        }
        else if (syncState.getUpdateCount()==lastUpdateCount)
            // nothing happened since we last updated
            return;
        else
            performSync(updateInfo, noteStore, false);
    }

    private NoteStoreClient getNoteStoreClient(final UpdateInfo updateInfo) throws EDAMUserException, EDAMSystemException, TException {
        final Boolean sandbox = Boolean.valueOf(guestService.getApiKeyAttribute(updateInfo.apiKey, EvernoteController.EVERNOTE_SANDBOX_KEY));
        String token = guestService.getApiKeyAttribute(updateInfo.apiKey, "accessToken");
        EvernoteAuth evernoteAuth = new EvernoteAuth(sandbox?EvernoteService.SANDBOX:EvernoteService.PRODUCTION, token);
        ClientFactory factory = new ClientFactory(evernoteAuth);
        return factory.createNoteStoreClient();
    }

    private void performSync(final UpdateInfo updateInfo, final NoteStoreClient noteStore, final boolean fullSync) throws Exception {
        try {
            // retrieve lastUpdateCount - this could be an incremental update or
            // a second attempt at a previously failed history update
            final String lastUpdateCountAtt = guestService.getApiKeyAttribute(updateInfo.apiKey, LAST_UPDATE_COUNT);
            int lastUpdateCount = 0;
            if (lastUpdateCountAtt!=null)
                lastUpdateCount = Integer.valueOf(lastUpdateCountAtt);

            // retrieve sync chunks at once
            LinkedList<SyncChunk> chunks = getSyncChunks(noteStore, lastUpdateCount, fullSync);

            createOrUpdateTags(updateInfo, chunks);
            createOrUpdateNotebooks(updateInfo, chunks);
            createOrUpdateNotes(updateInfo, chunks, noteStore);

            // process expunged items in the case of an incremental update and we are not required
            // to do a full sync (in which case it would be a no-op)
            if (updateInfo.getUpdateType()==UpdateInfo.UpdateType.INCREMENTAL_UPDATE && !fullSync) {
                processExpungedNotes(updateInfo, chunks);
                processExpungedNotebooks(updateInfo, chunks);
                processExpungedTags(updateInfo, chunks);
            }

            saveSyncState(updateInfo, noteStore);
        } catch (EDAMSystemException e) {
            // if rate limit has been reached, EN will send us the time when we can call the API again
            // and we can explicitely inform the userInfo object of it
            if (e.getErrorCode()== EDAMErrorCode.RATE_LIMIT_REACHED) {
                updateInfo.setResetTime("all", System.currentTimeMillis()+e.getRateLimitDuration()*1000);
                throw new RateLimitReachedException();
            }
        } catch (EDAMUserException e) {
            // if the auth token expired, we have no other choice than to have the user re-authenticate
            if (e.getErrorCode()==EDAMErrorCode.AUTH_EXPIRED) {
                throw new AuthExpiredException();
            }
        }
    }

    private void processExpungedNotes(final UpdateInfo updateInfo, final LinkedList<SyncChunk> chunks) {
        List<String> expungedNoteGuids = new ArrayList<String>();
        for (SyncChunk chunk : chunks) {
            final List<String> chunkExpungedNotes = chunk.getExpungedNotes();
            if (chunkExpungedNotes!=null)
                expungedNoteGuids.addAll(chunkExpungedNotes);
        }
        for (String expungedNoteGuid : expungedNoteGuids) {
            removeNote(updateInfo, expungedNoteGuid);
        }
    }

    /**
     * Delete a note, its associated resources and their dependent files from permanent storage
     * @param updateInfo
     * @param noteGuid
     */
    private void removeNote(final UpdateInfo updateInfo, final String noteGuid) {
        // first remove the note itself
        removeEvernoteFacet(updateInfo, EvernoteNoteFacet.class, noteGuid);
        // now retrieve the info needed to figure out what its associated resources and their dependent files are
        final List resourceInfos = jpaDaoService.executeNativeQuery(String.format("SELECT guid, mime FROM %s facet WHERE facet.apiKeyId=(?1) AND facet.noteGuid=(?2)",
                                                                                  JPAUtils.getEntityName(EvernoteResourceFacet.class)),
                                                                    updateInfo.apiKey.getId(), noteGuid);
        final String connectorDataLocation = env.get("connectorData.location");
        for (Object infos : resourceInfos) {
            Object[] guidAndMime = (Object[]) infos;
            String guid = (String)guidAndMime[0];
            String mime = (String)guidAndMime[1];
            // remove the resource from the database first
            removeEvernoteFacet(updateInfo, EvernoteResourceFacet.class, guid);
            // now retrieve the associated data files and delete them if they exist
            final File resourceDataFile = getResourceFile(updateInfo.apiKey.getId(), guid, MAIN_APPENDIX, mime, connectorDataLocation);
            final File resourceAlternateDataFile = getResourceFile(updateInfo.apiKey.getId(), guid, ALTERNATE_APPENDIX, mime, connectorDataLocation);
            final File resourceRecognitionDataFile = getResourceFile(updateInfo.apiKey.getId(), guid, RECOGNITION_APPENDIX, mime, connectorDataLocation);
            if (resourceDataFile.exists())
                resourceDataFile.delete();
            if (resourceAlternateDataFile.exists())
                resourceAlternateDataFile.delete();
            if (resourceRecognitionDataFile.exists())
                resourceRecognitionDataFile.delete();
        }
    }

    private void processExpungedNotebooks(final UpdateInfo updateInfo, final LinkedList<SyncChunk> chunks) {
        List<String> expungedNotebookGuids = new ArrayList<String>();
        for (SyncChunk chunk : chunks) {
            final List<String> chunkExpungedNotebooks = chunk.getExpungedNotebooks();
            if (chunkExpungedNotebooks!=null)
                expungedNotebookGuids.addAll(chunkExpungedNotebooks);
        }
        for (String expungedNotebookGuid : expungedNotebookGuids)
            removeNotebook(updateInfo, expungedNotebookGuid);
    }

    private void processExpungedTags(final UpdateInfo updateInfo, final LinkedList<SyncChunk> chunks) {
        List<String> expungedTagGuids = new ArrayList<String>();
        for (SyncChunk chunk : chunks) {
            final List<String> chunkExpungedNotes = chunk.getExpungedNotes();
            if (chunkExpungedNotes!=null)
                expungedTagGuids.addAll(chunkExpungedNotes);
        }
        for (String expungedTagGuid : expungedTagGuids)
            removeEvernoteFacet(updateInfo, EvernoteTagFacet.class, expungedTagGuid);
    }

    private void createOrUpdateTags(final UpdateInfo updateInfo, final LinkedList<SyncChunk> chunks) throws Exception {
        List<Tag> tags = new ArrayList<Tag>();
        List<String> expungedTagGuids = new ArrayList<String>();
        for (SyncChunk chunk : chunks){
            final List<Tag> chunkTags = chunk.getTags();
            if (chunkTags!=null)
                tags.addAll(chunkTags);
            final List<String> expungedTags = chunk.getExpungedTags();
            if (expungedTags!=null)
                expungedTagGuids.addAll(expungedTags);
        }
        for (Tag tag : tags) {
            if (!expungedTagGuids.contains(tag.getGuid()))
                createOrUpdateTag(updateInfo, tag);
        }
    }

    private void createOrUpdateNotebooks(final UpdateInfo updateInfo, final LinkedList<SyncChunk> chunks) throws Exception {
        List<Notebook> notebooks = new ArrayList<Notebook>();
        List<String> expungedNotebookGuids = new ArrayList<String>();
        for (SyncChunk chunk : chunks){
            final List<Notebook> chunkNotebooks = chunk.getNotebooks();
            if (chunkNotebooks!=null)
                notebooks.addAll(chunkNotebooks);
            final List<String> expungedNotebooks = chunk.getExpungedNotebooks();
            if (expungedNotebooks!=null)
            expungedNotebookGuids.addAll(expungedNotebooks);
        }
        for (Notebook notebook : notebooks) {
            if (!expungedNotebookGuids.contains(notebook.getGuid()))
                createOrUpdateNotebook(updateInfo, notebook);
        }
    }

    private void createOrUpdateNotes(final UpdateInfo updateInfo, final LinkedList<SyncChunk> chunks,
                                     NoteStoreClient noteStore) throws Exception {
        List<Note> notes = new ArrayList<Note>();
        List<String> expungedNoteGuids = new ArrayList<String>();
        for (SyncChunk chunk : chunks){
            final List<Note> chunkNotes = chunk.getNotes();
            if (chunkNotes!=null)
                notes.addAll(chunkNotes);
            final List<String> chunkExpungedNotes = chunk.getExpungedNotes();
            if (chunkExpungedNotes!=null)
                expungedNoteGuids.addAll(chunkExpungedNotes);
        }
        for (Note note : notes) {
            if (!expungedNoteGuids.contains(note.getGuid()))
                createOrUpdateNote(updateInfo, note, noteStore);
        }
    }

    private LinkedList<SyncChunk> getSyncChunks(final NoteStoreClient noteStore, final int lastUpdateCount, final boolean fullSync) throws EDAMUserException, EDAMSystemException, TException {
        LinkedList<SyncChunk> chunks = new LinkedList<SyncChunk>();
        SyncChunk chunk = noteStore.getSyncChunk(lastUpdateCount, MAX_ENTRIES, fullSync);
        if (chunk!=null) {
            chunks.add(chunk);
            while (chunk.getChunkHighUSN()<chunk.getUpdateCount()) {
                chunk = noteStore.getSyncChunk(chunk.getChunkHighUSN(), MAX_ENTRIES, fullSync);
                if (chunk!=null)
                    chunks.add(chunk);
            }
        }
        return chunks;
    }

    private void saveSyncState(final UpdateInfo updateInfo, NoteStoreClient noteStore) throws TException, EDAMUserException, EDAMSystemException {
        final SyncState syncState = noteStore.getSyncState();
        int serviceLastUpdateCount = syncState.getUpdateCount();
        final long serviceLastSyncTime = syncState.getCurrentTime();
        final String lastUpdateCountAtt = String.valueOf(serviceLastUpdateCount);
        guestService.setApiKeyAttribute(updateInfo.apiKey, LAST_UPDATE_COUNT, lastUpdateCountAtt);
        final String lastSyncTimeAtt = String.valueOf(serviceLastSyncTime);
        guestService.setApiKeyAttribute(updateInfo.apiKey, LAST_SYNC_TIME, lastSyncTimeAtt);
    }

    private void createOrUpdateNote(final UpdateInfo updateInfo, final Note note, final NoteStoreClient noteStore) throws Exception {
        final ApiDataService.FacetQuery facetQuery = new ApiDataService.FacetQuery(
                "e.apiKeyId=? AND e.guid=?",
                updateInfo.apiKey.getId(), note.getGuid());
        final ApiDataService.FacetModifier<EvernoteNoteFacet> facetModifier = new ApiDataService.FacetModifier<EvernoteNoteFacet>() {
            @Override
            public EvernoteNoteFacet createOrModify(EvernoteNoteFacet facet, final Long apiKeyId) throws Exception {
                if (facet == null) {
                    facet = new EvernoteNoteFacet(updateInfo.apiKey.getId());
                    extractCommonFacetData(facet, updateInfo);
                    facet.guid = note.getGuid();
                }
                if ((facet.contentHash==null||!Arrays.equals(facet.contentHash, note.getContentHash())||
                    facet.contentLength!=note.getContentLength())||
                    facet.USN<note.getUpdateSequenceNum()){
                }
                Note freshlyRetrievedNote = noteStore.getNote(note.getGuid(), true, true, true, true);
                facet.timeUpdated = System.currentTimeMillis();
                if (freshlyRetrievedNote.isSetUpdateSequenceNum())
                    facet.USN = freshlyRetrievedNote.getUpdateSequenceNum();
                if (freshlyRetrievedNote.isSetContentHash())
                    facet.contentHash = freshlyRetrievedNote.getContentHash();
                if (freshlyRetrievedNote.isSetContentLength())
                    facet.contentLength = freshlyRetrievedNote.getContentLength();
                Map<String, String> mapHashtoURL = new HashMap<String, String>();
                if (freshlyRetrievedNote.isSetResources()) {
                    for (Resource resource : freshlyRetrievedNote.getResources()) {
                        createOrUpdateResource(updateInfo, resource);
                        String webResourcePath = new StringBuilder("/evernote/res/")
                                .append(updateInfo.apiKey.getId())
                                .append("/")
                                .append(resource.getGuid()).toString();
                        mapHashtoURL.put(resource.getGuid(), webResourcePath);
                    }
                }
                if (freshlyRetrievedNote.isSetContent()) {
                    facet.content = freshlyRetrievedNote.getContent();
                    final long then = System.currentTimeMillis();
                    // WARNING!! The first time this gets call, a lengthy DTD processing operation
                    // needs to happen which can take a long while (~1min) - after that the conversion
                    // from enml to xhtml is very fast
                    final String htmlContent = processor.noteToHTMLString(freshlyRetrievedNote, mapHashtoURL);
                    final long now = System.currentTimeMillis();
                    System.out.println("converting enml note took " + (now-then) + " ms");
                    facet.htmlContent = htmlContent;
                }
                facet.clearTags();
                if (freshlyRetrievedNote.isSetTagNames())
                    facet.addTags(StringUtils.join(freshlyRetrievedNote.getTagNames(), ","), ',');

                if (freshlyRetrievedNote.isSetTitle())
                    facet.title = freshlyRetrievedNote.getTitle();
                if (freshlyRetrievedNote.isSetCreated()) {
                    facet.created = freshlyRetrievedNote.getCreated();
                    facet.start = facet.created;
                    facet.end = facet.created;
                }
                if (freshlyRetrievedNote.isSetUpdated()) {
                    facet.updated = freshlyRetrievedNote.getUpdated();
                    facet.start = facet.updated;
                    facet.end = facet.updated;
                }
                if (freshlyRetrievedNote.isSetDeleted())
                    facet.deleted = freshlyRetrievedNote.getDeleted();
                else if (!freshlyRetrievedNote.isSetDeleted()&&facet.deleted!=null)
                    facet.deleted = null;
                if (freshlyRetrievedNote.isSetNotebookGuid())
                    facet.notebookGuid = freshlyRetrievedNote.getNotebookGuid();
                if (freshlyRetrievedNote.isSetActive())
                    facet.active = freshlyRetrievedNote.isActive();

                if (freshlyRetrievedNote.isSetAttributes()) {
                    final NoteAttributes attributes = freshlyRetrievedNote.getAttributes();
                    if (attributes.isSetAltitude())
                        facet.altitude = attributes.getAltitude();
                    if (attributes.isSetAuthor())
                        facet.author = attributes.getAuthor();
                    if (attributes.isSetContentClass())
                        facet.contentClass = attributes.getContentClass();
                    if (attributes.isSetCreatorId())
                        facet.creatorId = attributes.getCreatorId();
                    if (attributes.isSetLastEditedBy())
                        facet.lastEditedBy = attributes.getLastEditedBy();
                    if (attributes.isSetLastEditorId())
                        facet.lastEditorId = attributes.getLastEditorId();
                    if (attributes.isSetLatitude())
                        facet.latitude = attributes.getLatitude();
                    if (attributes.isSetLongitude())
                        facet.longitude = attributes.getLongitude();
                    if (attributes.isSetPlaceName())
                        facet.placeName = attributes.getPlaceName();
                    if (attributes.isSetReminderDoneTime())
                        facet.reminderDoneTime = attributes.getReminderDoneTime();
                    if (attributes.isSetReminderOrder())
                        facet.reminderOrder = attributes.getReminderOrder();
                    if (attributes.isSetReminderTime())
                        facet.reminderTime = attributes.getReminderTime();
                    if (attributes.isSetShareDate())
                        facet.shareDate = attributes.getShareDate();
                    if (attributes.isSetSource())
                        facet.source = attributes.getSource();
                    if (attributes.isSetSourceApplication())
                        facet.sourceApplication = attributes.getSourceApplication();
                    if (attributes.isSetSourceURL())
                        facet.sourceURL = attributes.getSourceURL();
                    if (attributes.isSetSubjectDate())
                        facet.subjectDate = attributes.getSubjectDate();
                    if (attributes.isSetLatitude()&&attributes.isSetLongitude()&&freshlyRetrievedNote.isSetCreated()){
                        addGuestLocation(updateInfo, facet.latitude, facet.longitude, facet.altitude, facet.created);
                    }
                }
                return facet;
            }
        };
        // we could use the resulting value (facet) from this call if we needed to do further processing on it (e.g. passing it on to the datastore)
        apiDataService.createOrReadModifyWrite(EvernoteNoteFacet.class, facetQuery, facetModifier, updateInfo.apiKey.getId());
    }

    private void createOrUpdateResource(final UpdateInfo updateInfo, final Resource resource) throws Exception {
        final ApiDataService.FacetQuery facetQuery = new ApiDataService.FacetQuery(
                "e.apiKeyId=? AND e.guid=?",
                updateInfo.apiKey.getId(), resource.getGuid());
        final ApiDataService.FacetModifier<EvernoteResourceFacet> facetModifier = new ApiDataService.FacetModifier<EvernoteResourceFacet>() {
            @Override
            public EvernoteResourceFacet createOrModify(EvernoteResourceFacet facet, final Long apiKeyId) throws Exception {
                if (facet == null) {
                    facet = new EvernoteResourceFacet(updateInfo.apiKey.getId());
                    extractCommonFacetData(facet, updateInfo);
                    facet.guid = resource.getGuid();
                }
                if (resource.isSetAlternateData()) {
                    Data alternateData = resource.getAlternateData();
                    if (alternateData.isSetBody()&&resource.isSetGuid())
                        saveDataBodyAsFile(updateInfo, resource.getGuid(), ALTERNATE_APPENDIX, alternateData.getBody(), resource.getMime());
                    if (alternateData.isSetBodyHash())
                        facet.alternateDataBodyHash = alternateData.getBodyHash();
                    if (alternateData.isSetSize())
                        facet.alternateDataSize = alternateData.getSize();
                }
                if (resource.isSetAttributes()) {
                    final ResourceAttributes resourceAttributes = resource.getAttributes();
                    if (resourceAttributes.isSetAltitude())
                        facet.altitude = resourceAttributes.getAltitude();
                    if (resourceAttributes.isSetAttachment())
                        facet.isAttachment = resourceAttributes.isAttachment();
                    if (resourceAttributes.isSetCameraMake())
                        facet.cameraMake = resourceAttributes.getCameraMake();
                    if (resourceAttributes.isSetCameraModel())
                        facet.cameraModel = resourceAttributes.getCameraModel();
                    if (resourceAttributes.isSetFileName())
                        facet.fileName = resourceAttributes.getFileName();
                    if (resourceAttributes.isSetLatitude())
                        facet.latitude = resourceAttributes.getLatitude();
                    if (resourceAttributes.isSetLongitude())
                        facet.longitude = resourceAttributes.getLongitude();
                    if (resourceAttributes.isSetRecoType())
                        facet.recoType = resourceAttributes.getRecoType();
                    if (resourceAttributes.isSetSourceURL())
                        facet.sourceURL = resourceAttributes.getSourceURL();
                    if (resourceAttributes.isSetTimestamp())
                        facet.timestamp = resourceAttributes.getTimestamp();
                    if (resourceAttributes.isSetTimestamp() &&
                        resourceAttributes.isSetLongitude() &&
                        resourceAttributes.isSetLatitude()){
                        addGuestLocation(updateInfo, facet.latitude, facet.longitude, facet.altitude, facet.timestamp);
                    }
                }
                if (resource.isSetData()) {
                    Data data = resource.getData();
                    if (data.isSetBody()&&resource.isSetGuid())
                        saveDataBodyAsFile(updateInfo, resource.getGuid(), MAIN_APPENDIX, data.getBody(), resource.getMime());
                    if (data.isSetBodyHash())
                        facet.dataBodyHash = data.getBodyHash();
                    if (data.isSetSize())
                        facet.dataSize = data.getSize();
                }
                if (resource.isSetHeight())
                    facet.height = resource.getHeight();
                if (resource.isSetMime())
                    facet.mime = resource.getMime();
                if (resource.isSetNoteGuid())
                    facet.noteGuid = resource.getNoteGuid();
                if (resource.isSetRecognition()) {
                    Data recognitionData = resource.getRecognition();
                    if (recognitionData.isSetBody()&&resource.isSetGuid())
                        saveDataBodyAsFile(updateInfo, resource.getGuid(), RECOGNITION_APPENDIX, recognitionData.getBody(), null);
                    if (recognitionData.isSetBodyHash())
                        facet.recognitionDataBodyHash = recognitionData.getBodyHash();
                    if (recognitionData.isSetSize())
                        facet.recognitionDataSize = recognitionData.getSize();
                }
                if (resource.isSetUpdateSequenceNum())
                    facet.USN = resource.getUpdateSequenceNum();
                if (resource.isSetWidth())
                    facet.width = resource.getWidth();
                return facet;
            }
        };
        apiDataService.createOrReadModifyWrite(EvernoteResourceFacet.class, facetQuery, facetModifier, updateInfo.apiKey.getId());
    }

    private void saveDataBodyAsFile(final UpdateInfo updateInfo, final String guid, final String appendix, final byte[] body, final String mimeType) throws IOException {
        final String connectorDataLocation = env.get("connectorData.location");
        if (connectorDataLocation==null)
            throw new RuntimeException("No connectorData.location property was specified (local.properties)");
        File file = getResourceFile(updateInfo.apiKey.getId(), guid, appendix, mimeType, connectorDataLocation);
        file.getParentFile().mkdirs();
        FileOutputStream fileoutput = new FileOutputStream(file);
        IOUtils.copy(new ByteArrayInputStream(body), fileoutput);
        fileoutput.close();
    }

    /**
     *
     * @param apiKeyId
     * @param guid
     * @param appendix
     * @param mimeType
     * @param connectorDataLocation
     * @return
     */
    public static File getResourceFile(final long apiKeyId, final String guid, final String appendix, final String mimeType, final String connectorDataLocation) {
        String extension = getFileExtension(mimeType);
        if (appendix.equals(RECOGNITION_APPENDIX))
            extension = ".xml";
        return new File(new StringBuilder(connectorDataLocation).append(File.separator)
                                     .append("evernote")
                                     .append(File.separator)
                                     .append(apiKeyId)
                                     .append(File.separator)
                                     .append(guid)
                                     .append(appendix.equals(MAIN_APPENDIX)?"":"_")
                                     .append(appendix.equals(MAIN_APPENDIX)?"":appendix)
                                     .append(extension).toString());
    }

    private static String getFileExtension(String mimeType) {
        if (StringUtils.isEmpty(mimeType)) return "";
        MimeTypes allTypes = MimeTypes.getDefaultMimeTypes();
        try {
            MimeType type = allTypes.forName(mimeType);
            return type.getExtension();
        }
        catch (MimeTypeException e) {
            return "";
        }
    }

    private void addGuestLocation(final UpdateInfo updateInfo, final Double latitude, final Double longitude, final Double altitude, final Long timestamp) {
        LocationFacet locationFacet = new LocationFacet(updateInfo.apiKey.getId());
        locationFacet.latitude = latitude.floatValue();
        locationFacet.longitude = longitude.floatValue();
        if (altitude!=null)
            locationFacet.altitude = altitude.intValue();
        locationFacet.timestampMs = timestamp;
        locationFacet.start = locationFacet.timestampMs;
        locationFacet.end = locationFacet.timestampMs;
        locationFacet.source = LocationFacet.Source.EVERNOTE;
        locationFacet.apiKeyId = updateInfo.apiKey.getId();
        locationFacet.api = connector().value();
        apiDataService.addGuestLocation(updateInfo.getGuestId(), locationFacet);
    }

    private void removeEvernoteFacet(final UpdateInfo updateInfo, Class<? extends EvernoteFacet> clazz, final String guid) {
        jpaDaoService.execute(String.format("DELETE FROM %s facet WHERE facet.apiKeyId=%s AND facet.guid='%s'",
                                            JPAUtils.getEntityName(clazz),
                                            updateInfo.apiKey.getId(),
                                            guid));
    }

    private void createOrUpdateNotebook(final UpdateInfo updateInfo, final Notebook notebook) throws Exception {
        final ApiDataService.FacetQuery facetQuery = new ApiDataService.FacetQuery(
                "e.apiKeyId=? AND e.guid=?",
                updateInfo.apiKey.getId(), notebook.getGuid());
        final ApiDataService.FacetModifier<EvernoteNotebookFacet> facetModifier = new ApiDataService.FacetModifier<EvernoteNotebookFacet>() {
            @Override
            public EvernoteNotebookFacet createOrModify(EvernoteNotebookFacet facet, final Long apiKeyId) throws Exception {
                if (facet == null) {
                    facet = new EvernoteNotebookFacet(updateInfo.apiKey.getId());
                    extractCommonFacetData(facet, updateInfo);
                    facet.guid = notebook.getGuid();
                }
                facet.timeUpdated = System.currentTimeMillis();
                if (notebook.isSetUpdateSequenceNum())
                    facet.USN = notebook.getUpdateSequenceNum();
                if (notebook.isSetName())
                    facet.name = notebook.getName();
                if (notebook.isSetServiceCreated()) {
                    facet.serviceCreated = notebook.getServiceCreated();
                }
                if (notebook.isSetServiceUpdated())
                    facet.serviceUpdated = notebook.getServiceUpdated();
                if (notebook.isSetPublishing())
                    facet.publishing = notebook.getPublishing();
                if (notebook.isSetPublished())
                    facet.published = notebook.isPublished();
                if (notebook.isSetStack())
                    facet.stack = notebook.getStack();
                if (notebook.isSetDefaultNotebook())
                    facet.defaultNotebook = notebook.isDefaultNotebook();
                //omitting business information: contact, businessNotebook
                //omitting sharedNotebooks
                return facet;
            }
        };
        // we could use the resulting value (facet) from this call if we needed to do further processing on it (e.g. passing it on to the datastore)
        apiDataService.createOrReadModifyWrite(EvernoteNotebookFacet.class, facetQuery, facetModifier, updateInfo.apiKey.getId());
    }

    private void removeNotebook(final UpdateInfo updateInfo, final String guid) {
        // remove the notebook itself
        removeEvernoteFacet(updateInfo, EvernoteNotebookFacet.class, guid);
        // now retrieve the guids of all the notes it contains so we can wipe them out along
        // with the resources they reference
        // Note: it is possible that these notes are part of the expunged notes list in
        // the SyncChunk already, so this might be unnecessary
        final List noteGuids = jpaDaoService.executeNativeQuery(
                String.format("SELECT guid FROM %s facet WHERE facet.apiKeyId=(?1) AND facet.notebookGuid=(?2)",
                              JPAUtils.getEntityName(EvernoteNoteFacet.class)),
                updateInfo.apiKey.getId(), guid);
        for (Object noteGuid : noteGuids) {
            // not sure if we are getting back an array of objects or just a string, let's test for both
            if (noteGuid instanceof String)
                removeNote(updateInfo, (String) noteGuid);
            else if (noteGuid instanceof Object[])
                removeNote(updateInfo, (String) ((Object[])noteGuid)[0]);
        }
    }

    private void createOrUpdateTag(final UpdateInfo updateInfo, final Tag tag) throws Exception {
        final ApiDataService.FacetQuery facetQuery = new ApiDataService.FacetQuery(
                "e.apiKeyId=? AND e.guid=?",
                updateInfo.apiKey.getId(), tag.getGuid());
        final ApiDataService.FacetModifier<EvernoteTagFacet> facetModifier = new ApiDataService.FacetModifier<EvernoteTagFacet>() {
            @Override
            public EvernoteTagFacet createOrModify(EvernoteTagFacet facet, final Long apiKeyId) {
                if (facet == null) {
                    facet = new EvernoteTagFacet(updateInfo.apiKey.getId());
                    extractCommonFacetData(facet, updateInfo);
                    facet.guid = tag.getGuid();
                }
                facet.timeUpdated = System.currentTimeMillis();
                if (tag.isSetUpdateSequenceNum())
                    facet.USN = tag.getUpdateSequenceNum();
                if (tag.isSetName())
                    facet.name = tag.getName();
                return facet;
            }
        };
        // we could use the resulting value (facet) from this call if we needed to do further processing on it (e.g. passing it on to the datastore)
        apiDataService.createOrReadModifyWrite(EvernoteTagFacet.class, facetQuery, facetModifier, updateInfo.apiKey.getId());
    }

}
