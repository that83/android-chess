package jwtc.android.chess.opening;

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import java.util.HashMap;

/**
 * ContentProvider for imported Lichess studies and chapters.
 * Patterned after PGNProvider / ChessPuzzleProvider for consistency.
 */
public class OpeningStudyProvider extends ContentProvider {

    public static final String AUTHORITY = "jwtc.android.chess.opening.OpeningStudyProvider";

    public static final Uri CONTENT_URI_STUDIES =
            Uri.parse("content://" + AUTHORITY + "/studies");
    public static final Uri CONTENT_URI_CHAPTERS =
            Uri.parse("content://" + AUTHORITY + "/chapters");

    private static final String TAG = "OpeningStudyProvider";

    private static final String DATABASE_NAME = "chess_openings.db";
    private static final int DATABASE_VERSION = 1;
    private static final String TABLE_STUDIES = "studies";
    private static final String TABLE_CHAPTERS = "chapters";

    private static final int STUDIES = 1;
    private static final int STUDIES_ID = 2;
    private static final int CHAPTERS = 3;
    private static final int CHAPTERS_ID = 4;

    public static final String COL_ID = "_id";

    // Studies columns
    public static final String COL_STUDY_ID = "study_id"; // lichess study id
    public static final String COL_STUDY_NAME = "study_name";
    public static final String COL_STUDY_URL = "study_url";
    public static final String COL_STUDY_OWNER = "study_owner";
    public static final String COL_STUDY_IMPORTED_AT = "imported_at";

    // Chapters columns
    public static final String COL_CHAPTER_ID = "chapter_id"; // lichess chapter id or synthetic
    public static final String COL_CHAPTER_NAME = "chapter_name";
    public static final String COL_CHAPTER_STUDY_ID = "study_id";
    public static final String COL_CHAPTER_PGN = "pgn";
    public static final String COL_CHAPTER_IMPORTED_AT = "imported_at";

    private static final String CONTENT_TYPE =
            "vnd.android.cursor.dir/vnd.jwtc.opening";
    private static final String CONTENT_ITEM_TYPE =
            "vnd.android.cursor.item/vnd.jwtc.opening";

    private static HashMap<String, String> sStudiesProjectionMap;
    private static HashMap<String, String> sChaptersProjectionMap;
    private static UriMatcher sUriMatcher;

    static {
        sUriMatcher = new UriMatcher(UriMatcher.NO_MATCH);
        sUriMatcher.addURI(AUTHORITY, "studies", STUDIES);
        sUriMatcher.addURI(AUTHORITY, "studies/#", STUDIES_ID);
        sUriMatcher.addURI(AUTHORITY, "chapters", CHAPTERS);
        sUriMatcher.addURI(AUTHORITY, "chapters/#", CHAPTERS_ID);

        sStudiesProjectionMap = new HashMap<>();
        sStudiesProjectionMap.put(COL_ID, COL_ID);
        sStudiesProjectionMap.put(COL_STUDY_ID, COL_STUDY_ID);
        sStudiesProjectionMap.put(COL_STUDY_NAME, COL_STUDY_NAME);
        sStudiesProjectionMap.put(COL_STUDY_URL, COL_STUDY_URL);
        sStudiesProjectionMap.put(COL_STUDY_OWNER, COL_STUDY_OWNER);
        sStudiesProjectionMap.put(COL_STUDY_IMPORTED_AT, COL_STUDY_IMPORTED_AT);

        sChaptersProjectionMap = new HashMap<>();
        sChaptersProjectionMap.put(COL_ID, COL_ID);
        sChaptersProjectionMap.put(COL_CHAPTER_ID, COL_CHAPTER_ID);
        sChaptersProjectionMap.put(COL_CHAPTER_NAME, COL_CHAPTER_NAME);
        sChaptersProjectionMap.put(COL_CHAPTER_STUDY_ID, COL_CHAPTER_STUDY_ID);
        sChaptersProjectionMap.put(COL_CHAPTER_PGN, COL_CHAPTER_PGN);
        sChaptersProjectionMap.put(COL_CHAPTER_IMPORTED_AT, COL_CHAPTER_IMPORTED_AT);
    }

    private static class DatabaseHelper extends SQLiteOpenHelper {

        DatabaseHelper(Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + TABLE_STUDIES + " ("
                    + COL_ID + " INTEGER PRIMARY KEY,"
                    + COL_STUDY_ID + " TEXT UNIQUE,"
                    + COL_STUDY_NAME + " TEXT,"
                    + COL_STUDY_URL + " TEXT,"
                    + COL_STUDY_OWNER + " TEXT,"
                    + COL_STUDY_IMPORTED_AT + " INTEGER"
                    + ");");

            db.execSQL("CREATE TABLE " + TABLE_CHAPTERS + " ("
                    + COL_ID + " INTEGER PRIMARY KEY,"
                    + COL_CHAPTER_ID + " TEXT UNIQUE,"
                    + COL_CHAPTER_NAME + " TEXT,"
                    + COL_CHAPTER_STUDY_ID + " TEXT,"
                    + COL_CHAPTER_PGN + " TEXT,"
                    + COL_CHAPTER_IMPORTED_AT + " INTEGER"
                    + ");");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            Log.w(TAG, "Upgrading database from version " + oldVersion + " to "
                    + newVersion + ", dropping all data");
            db.execSQL("DROP TABLE IF EXISTS " + TABLE_CHAPTERS);
            db.execSQL("DROP TABLE IF EXISTS " + TABLE_STUDIES);
            onCreate(db);
        }
    }

    private DatabaseHelper mOpenHelper;

    @Override
    public boolean onCreate() {
        mOpenHelper = new DatabaseHelper(getContext());
        return true;
    }

    @Override
    public String getType(Uri uri) {
        switch (sUriMatcher.match(uri)) {
            case STUDIES:
            case CHAPTERS:
                return CONTENT_TYPE;
            case STUDIES_ID:
            case CHAPTERS_ID:
                return CONTENT_ITEM_TYPE;
            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();

        switch (sUriMatcher.match(uri)) {
            case STUDIES:
                qb.setTables(TABLE_STUDIES);
                qb.setProjectionMap(sStudiesProjectionMap);
                break;
            case STUDIES_ID:
                qb.setTables(TABLE_STUDIES);
                qb.setProjectionMap(sStudiesProjectionMap);
                qb.appendWhere(COL_ID + "=" + uri.getPathSegments().get(1));
                break;
            case CHAPTERS:
                qb.setTables(TABLE_CHAPTERS);
                qb.setProjectionMap(sChaptersProjectionMap);
                break;
            case CHAPTERS_ID:
                qb.setTables(TABLE_CHAPTERS);
                qb.setProjectionMap(sChaptersProjectionMap);
                qb.appendWhere(COL_ID + "=" + uri.getPathSegments().get(1));
                break;
            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }

        String orderBy = TextUtils.isEmpty(sortOrder) ? COL_ID + " ASC" : sortOrder;
        SQLiteDatabase db = mOpenHelper.getReadableDatabase();
        Cursor c = qb.query(db, projection, selection, selectionArgs, null, null, orderBy);
        c.setNotificationUri(getContext().getContentResolver(), uri);
        return c;
    }

    @Override
    public Uri insert(Uri uri, ContentValues initialValues) {
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        String table;
        Uri contentUri;

        switch (sUriMatcher.match(uri)) {
            case STUDIES:
                table = TABLE_STUDIES;
                contentUri = CONTENT_URI_STUDIES;
                break;
            case CHAPTERS:
                table = TABLE_CHAPTERS;
                contentUri = CONTENT_URI_CHAPTERS;
                break;
            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }

        long rowId = db.insertWithOnConflict(table, null, initialValues, SQLiteDatabase.CONFLICT_REPLACE);
        if (rowId > 0) {
            Uri noteUri = ContentUris.withAppendedId(contentUri, rowId);
            getContext().getContentResolver().notifyChange(noteUri, null);
            return noteUri;
        }

        throw new SQLException("Failed to insert row into " + uri);
    }

    @Override
    public int delete(Uri uri, String where, String[] whereArgs) {
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        String table;
        switch (sUriMatcher.match(uri)) {
            case STUDIES:
                table = TABLE_STUDIES;
                break;
            case CHAPTERS:
                table = TABLE_CHAPTERS;
                break;
            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }
        int count = db.delete(table, where, whereArgs);
        getContext().getContentResolver().notifyChange(uri, null);
        return count;
    }

    @Override
    public int update(Uri uri, ContentValues values, String where, String[] whereArgs) {
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        String table;
        switch (sUriMatcher.match(uri)) {
            case STUDIES:
                table = TABLE_STUDIES;
                break;
            case CHAPTERS:
                table = TABLE_CHAPTERS;
                break;
            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }
        int count = db.update(table, values, where, whereArgs);
        getContext().getContentResolver().notifyChange(uri, null);
        return count;
    }
}

