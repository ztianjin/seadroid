package com.seafile.seadroid;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Iterator;

import com.seafile.seadroid.TransferManager.TaskState;
import com.seafile.seadroid.TransferManager.UploadTaskInfo;
import com.seafile.seadroid.account.Account;
import com.seafile.seadroid.data.DataManager;
import com.seafile.seadroid.data.DataManager.ProgressMonitor;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.util.Log;
import android.widget.RemoteViews;

/**
 * Manages file downloading and uploading.
 *
 * Currently use an AsyncTask for an file.
 */
public class TransferManager {

    private static final String DEBUG_TAG = "TransferManager";

    public enum TaskState { INIT, TRANSFERRING, FINISHED, CANCELLED, FAILED }

    public interface TransferListener {

        public void onFileUploadProgress(int taskID);
        public void onFileUploaded(int taskID);
        public void onFileUploadCancelled(int taskID);
        public void onFileUploadFailed(int taskID);

        public void onFileDownloaded(String repoID, String path, String fileID);
        public void onFileDownloadFailed(String repoID, String path, String fileID,
                long size, SeafException err);

    }

    private ArrayList<UploadTask> uploadTasks;
    private ArrayList<DownloadTask> downloadTasks;
    private int notificationID;
    TransferListener listener;

    public TransferManager() {
        notificationID = 0;
        uploadTasks = new ArrayList<UploadTask>();
        downloadTasks = new ArrayList<DownloadTask>();
        listener = null;
    }

    public void setListener(TransferListener listener) {
        this.listener = listener;
    }

    public void unsetListener() {
        listener = null;
    }

    public void addUploadTask(Account account, String repoID, String repoName,
                              String dir, String filePath) {
        UploadTask task = new UploadTask(account, repoID, repoName, dir, filePath);
        task.execute();
    }

    public void addDownloadTask(Account account, String repoID, String path,
            String fileID, long size) {
        // check duplication
        for (DownloadTask task : downloadTasks) {
            if (task.myFileID.equals(fileID))
                return;
        }
        DownloadTask task = new DownloadTask(account, repoID, path, fileID, size);
        task.execute();
    }

    private UploadTask getUploadTaskByID(int taskID) {
        for (UploadTask task : uploadTasks) {
            if (task.getTaskID() == taskID) {
                return task;
            }
        }
        return null;
    }

    public UploadTaskInfo getUploadTaskInfo (int taskID) {
        UploadTask task = getUploadTaskByID(taskID);
        if (task != null) {
            return task.getTaskInfo();
        }

        return null;
    }

    public List<UploadTaskInfo> getAllUploadTaskInfos() {
        ArrayList<UploadTaskInfo> infos = new ArrayList<UploadTaskInfo>();
        for (UploadTask task : uploadTasks) {
            infos.add(task.getTaskInfo());
        }

        return infos;
    }

    public void removeUploadTask(int taskID) {
        UploadTask task = getUploadTaskByID(taskID);
        if (task != null) {
            uploadTasks.remove(task);
        }
    }

    public void removeFinishedUploadTasks() {
        Iterator<UploadTask> iter = uploadTasks.iterator();
        while (iter.hasNext()) {
            UploadTask task = iter.next();
            if (task.getState() == TaskState.FINISHED) {
                iter.remove();
            }
        }
    }

    public void cancelUploadTask(int taskID) {
        UploadTask task = getUploadTaskByID(taskID);
        if (task != null) {
            task.cancelUpload();
        }
    }

    public void retryUploadTask(int taskID) {
        UploadTask task = getUploadTaskByID(taskID);
        if (task != null) {
            task.retryUpload();
        }
    }

    private class UploadTask extends AsyncTask<String, Long, Void> {

        private String myRepoID;
        private String myRepoName;
        private String myDir;   // parent dir
        private String myPath;  // local file path
        private String myFileName; // base file name

        private TaskState myState;
        private int myID;
        private long myUploaded;
        private long mySize;

        SeafException err;

        Account account;

        public UploadTask(Account account, String repoID, String repoName,
                          String dir, String filePath) {
            this.account = account;
            this.myRepoID = repoID;
            this.myRepoName = repoName;
            this.myDir = dir;
            this.myPath = filePath;
            this.myFileName = Utils.fileNameFromPath(filePath);
            File f = new File(filePath);
            mySize = f.length();

            myID = ++notificationID;
            myState = TaskState.INIT;
            myUploaded = 0;

            // Log.d(DEBUG_TAG, "stored object is " + myPath + myObjectID);
            uploadTasks.add(this);
            err = null;
        }

        public int getTaskID() {
            return myID;
        }

        public TaskState getState() {
            return myState;
        }

        public UploadTaskInfo getTaskInfo() {
            UploadTaskInfo info = new UploadTaskInfo(myID, myState, myRepoID,
                                                     myRepoName, myDir, myPath,
                                                     myUploaded, mySize, err);
            return info;
        }

        public void retryUpload() {
            if (myState != TaskState.CANCELLED && myState != TaskState.FAILED) {
                return;
            }
            uploadTasks.remove(this);
            addUploadTask(account, myRepoID, myRepoName, myDir, myPath);
        }

        public void cancelUpload() {
            if (myState != TaskState.INIT && myState != TaskState.TRANSFERRING) {
                return;
            }
            myState = TaskState.CANCELLED;
            super.cancel(true);
        }

        @Override
        protected void onPreExecute() {
            myState = TaskState.TRANSFERRING;
        }

        @Override
        protected void onProgressUpdate(Long... values) {
            long uploaded = values[0];
            myUploaded = uploaded;
            listener.onFileUploadProgress(myID);
        }

        @Override
        protected Void doInBackground(String... params) {
            try {
                DataManager dataManager = new DataManager(account);
                dataManager.uploadFile(myRepoID, myDir, myPath,
                        new ProgressMonitor() {

                            @Override
                            public void onProgressNotify(long uploaded) {
                                publishProgress(uploaded);
                            }

                            @Override
                            public boolean isCancelled() {
                                return UploadTask.this.isCancelled();
                            }
                        }
                );
            } catch (SeafException e) {
                err = e;
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void v) {
            if (listener != null) {
                if (err == null) {
                    myState = TaskState.FINISHED;
                    listener.onFileUploaded(myID);
                }
                else {
                    myState = TaskState.FAILED;
                    listener.onFileUploadFailed(myID);
                }
            }
        }

        @Override
        protected void onCancelled() {
            if (listener != null) {
                listener.onFileUploadCancelled(myID);
            }
        }
    }

    private class DownloadTask extends AsyncTask<String, Integer, File> {

        Notification notification;
        NotificationManager notificationManager;
        private int showProgressThreshold = 1024 * 100; // 100KB
        private int myNtID;

        Account account;
        private String myRepoID;
        private String myPath;
        private String myFileID;
        private long mySize;
        SeafException err;

        public DownloadTask(Account account, String repoID, String path,
                String fileID, long size) {
            this.account = account;
            this.myRepoID = repoID;
            this.myPath = path;
            this.myFileID = fileID;
            this.mySize = size;
            // Log.d(DEBUG_TAG, "stored object is " + myPath + myObjectID);
            downloadTasks.add(this);
            err = null;
        }

        public String getFileID() {
            return myFileID;
        }

        @Override
        protected void onPreExecute() {
            if (mySize <= showProgressThreshold)
                return;
            myNtID = ++notificationID;

            Context context =  SeadroidApplication.getAppContext();
            notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            Intent notificationIntent = new Intent(context,
                    BrowserActivity.class);

            PendingIntent intent = PendingIntent.getActivity(context, myNtID, notificationIntent, 0);

            notification = new Notification(R.drawable.ic_stat_download, "", System.currentTimeMillis());
            notification.flags = notification.flags | Notification.FLAG_ONGOING_EVENT;
            notification.contentView = new RemoteViews(context.getPackageName(),
                    R.layout.download_progress);
            notification.contentView.setCharSequence(R.id.tv_download_title, "setText",
                    Utils.fileNameFromPath(myPath));
            notification.contentIntent = intent;

            notification.contentView.setProgressBar(R.id.pb_download_progressbar,
                    (int)mySize, 0, false);
            notificationManager.notify(myNtID, notification);
        }

        @Override
        protected void onProgressUpdate(Integer... values) {
            int progress = values[0];
            notification.contentView.setProgressBar(R.id.pb_download_progressbar,
                    (int)mySize, progress, false);
            notificationManager.notify(myNtID, notification);
        }

        @Override
        protected File doInBackground(String... params) {
            try {
                DataManager dataManager = new DataManager(account);
                if (mySize <= showProgressThreshold)
                    return dataManager.getFile(myRepoID, myPath, myFileID, null);
                else
                    return dataManager.getFile(myRepoID, myPath, myFileID,
                            new ProgressMonitor() {

                                @Override
                                public void onProgressNotify(long total) {
                                    publishProgress((int) total);
                                }

                                @Override
                                public boolean isCancelled() {
                                    return DownloadTask.this.isCancelled();
                                }
                            }
                            );
            } catch (SeafException e) {
                err = e;
                return null;
            }
        }

        @Override
        protected void onPostExecute(File file) {
            if (mySize > showProgressThreshold)
                notificationManager.cancel(myNtID);
            downloadTasks.remove(this);

            if (listener != null) {
                if (file != null)
                    listener.onFileDownloaded(myRepoID, myPath, myFileID);
                else {
                    if (err == null)
                        err = SeafException.unknownException;
                    listener.onFileDownloadFailed(myRepoID, myPath, myFileID, mySize, err);
                }
            }
        }

        @Override
        protected void onCancelled() {
            if (mySize > showProgressThreshold)
                notificationManager.cancel(myNtID);
            downloadTasks.remove(this);
        }

    }

    public class UploadTaskInfo {
        public final int taskID;
        public final TaskState state;
        public final String repoID;
        public final String repoName;
        public final String parentDir;
        public final String localFilePath;
        public final long uploadedSize, totalSize;
        public final SeafException err;

        public UploadTaskInfo(int taskID, TaskState state, String repoID,
                              String repoName, String parentDir, String localFilePath,
                              long uploadedSize, long totalSize, SeafException err) {
            this.taskID = taskID;
            this.state = state;
            this.repoID = repoID;
            this.repoName = repoName;
            this.parentDir = parentDir;
            this.localFilePath = localFilePath;
            this.uploadedSize = uploadedSize;
            this.totalSize = totalSize;
            this.err = err;
        }
    }
}
