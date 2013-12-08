package org.kiwix.kiwixmobile;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.Parcel;
import android.os.Parcelable;
import android.provider.MediaStore;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;
import android.support.v4.widget.SimpleCursorAdapter;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Adapter;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.io.FilenameFilter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Vector;

public class ZimFileSelectActivity extends FragmentActivity
        implements LoaderManager.LoaderCallbacks<Cursor>, OnItemClickListener {

    private static final int LOADER_ID = 0x02;

    // Array of zim file extensions
    private static final String[] zimFiles = {"zim", "zimaa"};

    // Adapter of the Data populated by the MediaStore
    private SimpleCursorAdapter mCursorAdapter;

    // Adapter of the Data populated by recanning the Filesystem by ourselves
    private RescanDataAdapter mRescanAdapter;

    private ArrayList<DataModel> mFiles;

    private ListView mZimFileList;

    private ProgressBar mProgressBar;

    private TextView mProgressBarMessage;

    private boolean mNeedsUpdate = false;

    private boolean mAdapterRefreshed = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.zimfilelist);

        mProgressBar = (ProgressBar) findViewById(R.id.progressBar);
        mProgressBarMessage = (TextView) findViewById(R.id.progressbar_message);

        mProgressBar.setVisibility(View.VISIBLE);

        mZimFileList = (ListView) findViewById(R.id.zimfilelist);
        mFiles = new ArrayList<DataModel>();

        selectZimFile();
    }

    private void finishResult(String path) {
        // Add new files to MediaStore
        addDataToMediaStore(mFiles);
        // Remove the nonexistent files from the MediaStore
        removeNonExistentFiles(mCursorAdapter.getCursor());
        if (path != null) {
            File file = new File(path);
            Uri uri = Uri.fromFile(file);
            Log.i("kiwix", "Opening " + uri);
            setResult(RESULT_OK, new Intent().setData(uri));
            finish();
        } else {
            setResult(RESULT_CANCELED);
            finish();
        }
    }

    protected void selectZimFile() {

        // Stop endless loops
        if (mAdapterRefreshed) {
            return;
        } else {
            mAdapterRefreshed = true;
        }

        // Defines a list of columns to retrieve from the Cursor and load into an output row
        String[] mZimListColumns = {MediaStore.Files.FileColumns.TITLE, MediaStore.Files.FileColumns.DATA};

        // Defines a list of View IDs that will receive the Cursor columns for each row
        int[] mZimListItems = {android.R.id.text1, android.R.id.text2};

        mCursorAdapter = new SimpleCursorAdapter(
                // The Context object
                ZimFileSelectActivity.this,
                // A layout in XML for one row in the ListView
                android.R.layout.simple_list_item_2,
                // The cursor, swapped later by cursorloader
                null,
                // A string array of column names in the cursor
                mZimListColumns,
                // An integer array of view IDs in the row layout
                mZimListItems,
                // Flags for the Adapter
                Adapter.NO_SELECTION);

        mZimFileList.setOnItemClickListener(this);

        getSupportLoaderManager().initLoader(LOADER_ID, null, this);
    }

    @Override
    public Loader<Cursor> onCreateLoader(int i, Bundle bundle) {
        Uri uri = MediaStore.Files.getContentUri("external");

        String[] projection = {
                MediaStore.Files.FileColumns._ID,
                // File Name
                MediaStore.Files.FileColumns.TITLE,
                // File Path
                MediaStore.Files.FileColumns.DATA
        };

        // Exclude media files, they would be here also (perhaps
        // somewhat better performance), and filter for zim files
        // (normal and first split)
        String query = MediaStore.Files.FileColumns.MEDIA_TYPE + "="
                + MediaStore.Files.FileColumns.MEDIA_TYPE_NONE + " AND"
                + " ( LOWER(" +
                MediaStore.Images.Media.DATA + ") LIKE '%." + zimFiles[0] + "'"
                + " OR LOWER(" +
                MediaStore.Images.Media.DATA + ") LIKE '%." + zimFiles[1] + "'"
                + " ) ";

        String[] selectionArgs = null; // There is no ? in query so null here

        String sortOrder = MediaStore.Images.Media.TITLE; // Sorted alphabetical
        Log.d("kiwix", " Performing query for zim files...");

        return new CursorLoader(this, uri, projection, query, selectionArgs, sortOrder);
    }

    @Override
    public void onLoadFinished(Loader<Cursor> cursorLoader, Cursor cursor) {
        Log.d("kiwix", "DONE querying Mediastore for .zim files");
        buildArrayAdapter(cursor);
        mCursorAdapter.swapCursor(cursor);
        mRescanAdapter = buildArrayAdapter(cursor);
        mZimFileList.setAdapter(mRescanAdapter);

        // Done here to avoid that shown while loading.
        mZimFileList.setEmptyView(findViewById(R.id.zimfilelist_nozimfilesfound_view));

        if (mProgressBarMessage.getVisibility() == View.GONE) {
            mProgressBar.setVisibility(View.GONE);
        }

        mCursorAdapter.notifyDataSetChanged();
    }

    // Get the data of our cursor and wrap it all in our ArrayAdapter.
    // We are doing this because the CursorAdapter does not allow us do remove rows from its dataset.
    private RescanDataAdapter buildArrayAdapter(Cursor cursor) {

        ArrayList<DataModel> files = new ArrayList<DataModel>();

        for (cursor.moveToFirst(); !cursor.isAfterLast(); cursor.moveToNext()) {

            if (new File(cursor.getString(2)).exists()) {
                files.add(new DataModel(cursor.getString(1), cursor.getString(2)));
            }
        }

        files = new FileWriter(ZimFileSelectActivity.this, files).getDataModelList();

        for (int i = 0; i < files.size(); i++) {

            if (!new File(files.get(i).getPath()).exists()) {
                Log.e("kiwix", "File removed: " + files.get(i).getTitle());
                files.remove(i);
            }
        }

        files = sortDataModel(files);
        mFiles = files;

        return new RescanDataAdapter(ZimFileSelectActivity.this, 0, mFiles);
    }

    // Connect to the MediaScannerConnection service and scan all the files, that are returned to us by
    // our MediaStore query. The file will ideally get removed from the MediaStore,
    // if the scan resturns null and our CursorAdapter will update.
    private void removeNonExistentFiles(Cursor cursor) {

        ArrayList<String> files = new ArrayList<String>();

        // Iterate trough the data from our curser and add every file path column to an ArrayList
        for (cursor.moveToFirst(); !cursor.isAfterLast(); cursor.moveToNext()) {
            files.add(cursor.getString(2));
        }
        updateMediaStore(files);

    }

    // Add new files to the MediaStore
    private void addDataToMediaStore(ArrayList<DataModel> files) {

        ArrayList<String> paths = new ArrayList<String>();

        for (DataModel file : files) {
            paths.add(file.getPath());
        }
        updateMediaStore(paths);
    }

    private void updateMediaStore(ArrayList<String> files) {

        // Abort endless loops. Let this update process only run on rescan.
        if (!mNeedsUpdate) {
            return;
        }

        Log.i("kiwix", "Updating MediaStore");

        // Scan every file (and delete it from the MediaStore, if it does not exist)
        MediaScannerConnection.scanFile(
                ZimFileSelectActivity.this,
                files.toArray(new String[files.size()]),
                null,
                new MediaScannerConnection.OnScanCompletedListener() {
                    @Override
                    public void onScanCompleted(String path, Uri uri) {

                    }
                });

        mNeedsUpdate = false;
    }

    public ArrayList<DataModel> sortDataModel(ArrayList<DataModel> data) {

        // Sorting the data in alphabetical order
        Collections.sort(data, new Comparator<DataModel>() {
            @Override
            public int compare(DataModel a, DataModel b) {
                return a.getTitle().compareToIgnoreCase(b.getTitle());
            }
        });

        return data;
    }

    @Override
    public void onLoaderReset(Loader<Cursor> cursorLoader) {
        mCursorAdapter.swapCursor(null);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {

        // Check, if the user has rescanned the file system, if he has, then we want to save this list,
        // so this can be shown again, if the actvitity is recreated (on a device rotation for example)
        if (!mFiles.isEmpty()) {
            Log.i("kiwix", "Saved state of the ListView");
            outState.putParcelableArrayList("rescanData", mFiles);
        }
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {

        // Get the rescanned data, if available. Create an Adapter for the ListView and display the list
        if (savedInstanceState.getParcelableArrayList("rescanData") != null) {
            ArrayList<DataModel> data = savedInstanceState.getParcelableArrayList("rescanData");
            mRescanAdapter = new RescanDataAdapter(ZimFileSelectActivity.this, 0, data);

            mZimFileList.setAdapter(mRescanAdapter);
        }
        super.onRestoreInstanceState(savedInstanceState);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        final MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.fileselector, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {

        switch (item.getItemId()) {
            case R.id.menu_rescan_fs:
                // Execute our AsyncTask, that scans the file system for the actual data
                new RescanFileSystem().execute();

                // Make sure, that we set mNeedsUpdate to true and to false, after the MediaStore has been
                // updated. Otherwise it will result in a endless loop.
                mNeedsUpdate = true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {

        Log.d("kiwix", " mZimFileList.onItemClick");

        String file;

        // Check which one of the Adapters is currently filling the ListView.
        // If the data is populated by the LoaderManager cast the current selected item to Cursor,
        // if the data is populated by the ArrayAdapter, then cast it to the DataModel class.
        if (mZimFileList.getItemAtPosition(position) instanceof DataModel) {

            DataModel data = (DataModel) mZimFileList.getItemAtPosition(position);
            file = data.getPath();

        } else {
            Cursor cursor = (Cursor) mZimFileList.getItemAtPosition(position);
            file = cursor.getString(2);
        }

        finishResult(file);
    }

    // This items class stores the Data for the ArrayAdapter.
    // We Have to implement Parcelable, so we can store ArrayLists with this generic type in the Bundle
    // of onSaveInstanceState() and retrieve it later on in onRestoreInstanceState()
    public static class DataModel implements Parcelable {

        // Interface that must be implemented and provided as a public CREATOR field.
        // It generates instances of our Parcelable class from a Parcel.
        public Parcelable.Creator<DataModel> CREATOR = new Parcelable.Creator<DataModel>() {

            @Override
            public DataModel createFromParcel(Parcel source) {
                return new DataModel(source);
            }

            @Override
            public boolean equals(Object o) {
                return super.equals(o);
            }

            @Override
            public int hashCode() {
                return super.hashCode();
            }

            @Override
            public DataModel[] newArray(int size) {
                return new DataModel[size];
            }
        };

        private String mTitle;

        private String mPath;

        public DataModel(String title, String path) {
            mTitle = title;
            mPath = path;
        }

        // This constructor will be called when this class is generated by a Parcel.
        // We have to read the previously written Data in this Parcel.
        public DataModel(Parcel parcel) {
            String[] data = new String[2];
            parcel.readStringArray(data);
            mTitle = data[0];
            mTitle = data[1];
        }

        public String getTitle() {
            return mTitle;
        }

        public String getPath() {
            return mPath;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            // Write the data to the Parcel, so we can restore this Data later on.
            // It will be restored by the DataModel(Parcel parcel) constructor.
            dest.writeArray(new String[]{mTitle, mPath});
        }

        // Override equals(Object) so we can compare objects. Specifically, so List#contains() works.
        @Override
        public boolean equals(Object object) {
            boolean isEqual = false;

            if (object != null && object instanceof DataModel) {
                isEqual = (this.mPath.equals(((DataModel) object).mPath));
            }

            return isEqual;
        }
    }

    // This AsyncTask will scan the file system for files with the Extension ".zim" or ".zimaa"
    private class RescanFileSystem extends AsyncTask<Void, Void, Void> {

        @Override
        protected void onPreExecute() {

            mProgressBarMessage.setVisibility(View.VISIBLE);
            mProgressBar.setVisibility(View.VISIBLE);

            super.onPreExecute();
        }

        @Override
        protected Void doInBackground(Void... params) {

            mFiles = FindFiles();
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            mRescanAdapter = new RescanDataAdapter(ZimFileSelectActivity.this, 0, mFiles);

            mZimFileList.setAdapter(mRescanAdapter);

            mProgressBarMessage.setVisibility(View.GONE);
            mProgressBar.setVisibility(View.GONE);

            new FileWriter(ZimFileSelectActivity.this).saveArray(mFiles);

            super.onPostExecute(result);
        }

        // Scan through the file system and find all the files with .zim and .zimaa extensions
        private ArrayList<DataModel> FindFiles() {
            String directory = new File(
                    Environment.getExternalStorageDirectory().getAbsolutePath()).toString();
            final List<String> fileList = new ArrayList<String>();
            FilenameFilter[] filter = new FilenameFilter[zimFiles.length];

            int i = 0;
            for (final String extension : zimFiles) {
                filter[i] = new FilenameFilter() {
                    public boolean accept(File dir, String name) {
                        return name.endsWith("." + extension);
                    }
                };
                i++;
            }

            File[] foundFiles = listFilesAsArray(new File(directory), filter, -1);
            for (File f : foundFiles) {
                fileList.add(f.getAbsolutePath());
            }

            return createDataForAdapter(fileList);
        }

        private Collection<File> listFiles(File directory, FilenameFilter[] filter,
                int recurse) {

            Vector<File> files = new Vector<File>();

            File[] entries = directory.listFiles();

            if (entries != null) {
                for (File entry : entries) {
                    for (FilenameFilter filefilter : filter) {
                        if (filter == null || filefilter.accept(directory, entry.getName())) {
                            files.add(entry);
                        }
                    }
                    if ((recurse <= -1) || (recurse > 0 && entry.isDirectory())) {
                        recurse--;
                        files.addAll(listFiles(entry, filter, recurse));
                        recurse++;
                    }
                }
            }
            return files;
        }

        public File[] listFilesAsArray(File directory, FilenameFilter[] filter, int recurse) {
            Collection<File> files = listFiles(directory, filter, recurse);

            File[] arr = new File[files.size()];
            return files.toArray(arr);
        }

        // Create an ArrayList with our DataModel
        private ArrayList<DataModel> createDataForAdapter(List<String> list) {

            ArrayList<DataModel> data = new ArrayList<DataModel>();
            for (String file : list) {

                data.add(new DataModel(getTitleFromFilePath(file), file));
            }

            data = sortDataModel(data);

            return data;
        }

        // Remove the file path and the extension and return a file name for the given file path
        private String getTitleFromFilePath(String path) {
            return new File(path).getName().replaceFirst("[.][^.]+$", "");
        }
    }

    // The Adapter for the ListView for when the ListView is populated with the rescanned files
    private class RescanDataAdapter extends ArrayAdapter<DataModel> {

        public RescanDataAdapter(Context context, int textViewResourceId, List<DataModel> objects) {
            super(context, textViewResourceId, objects);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {

            ViewHolder holder;

            // Check if we should inflate the layout for a new row, or if we can reuse a view.
            if (convertView == null) {
                convertView = View.inflate(getContext(), android.R.layout.simple_list_item_2, null);
                holder = new ViewHolder();
                holder.title = (TextView) convertView.findViewById(android.R.id.text1);
                holder.path = (TextView) convertView.findViewById(android.R.id.text2);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }
            holder.title.setText(getItem(position).getTitle());
            holder.path.setText(getItem(position).getPath());
            return convertView;
        }

        // We are using the ViewHolder pattern in order to optimize the ListView by reusing
        // Views and saving them to this item class, and not inlating the layout every time
        // we need to create a row.
        private class ViewHolder {

            TextView title;

            TextView path;
        }
    }
}
