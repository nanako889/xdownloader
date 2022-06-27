package com.qbw.xdownloader;

import android.app.DownloadManager;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.system.OsConstants;
import android.text.TextUtils;
import android.webkit.MimeTypeMap;


import com.qbw.l.L;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * @author qinbaowei
 * @date 2017/7/28
 * @email qbaowei@qq.com
 */

public class XDownloader {
    private static XDownloader sInst;

    public static void init(Context context) {
        sInst = new XDownloader(context);
    }

    private XDownloader(Context context) {
        mContext = context;
        HandlerThread thread = new HandlerThread("xdownloader");
        thread.start();
        mBackHandler = new Handler(thread.getLooper());
        mDownloadManager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
    }

    public static XDownloader getInstance() {
        return sInst;
    }

    private Context mContext;
    private final List<Listener> mListeners = new ArrayList<>();

    private final Handler mUiHandler = new Handler();
    private final Handler mBackHandler;

    private final DownloadManager mDownloadManager;

    private final List<Task> mTasks = new ArrayList<>();

    public void addTask(final Task task) {
        mBackHandler.post(() -> {
            synchronized (mTasks) {
                if (!hasTask(task.getDownloadUrl())) {
                    try {
                        doAddTask(task);
                    } catch (Exception e) {
                        e.printStackTrace();
                        L.GL.e(e);
                    }
                }
            }
        });
    }

    public boolean hasTask(String downloadUrl) {
        for (Task task : mTasks) {
            if (task.getDownloadUrl().equals(downloadUrl)) {
                return true;
            }
        }
        return false;
    }

    private void doAddTask(Task task) {
        File file = new File(task.getFileTargetDestination(mContext));
        if (file.exists()) {
            if (!task.mReDownloadWhenExist) {
                L.GL.w("target file[%s] exist", file.getAbsolutePath());
                notifyListener(new Status.Existed(task));
                return;
            }
        }

        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(task.getDownloadUrl()));
        request.setAllowedOverRoaming(false);

        request.setMimeType(MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(
                        task.getDownloadUrl())));

        //在通知栏中显示，默认就是显示的
        request.setNotificationVisibility(task.mShowNotification ? DownloadManager.Request.VISIBILITY_VISIBLE :
                DownloadManager.Request.VISIBILITY_HIDDEN);
        request.setVisibleInDownloadsUi(task.mShowNotification);

        String subPath;
        if (TextUtils.isEmpty(task.mSubDir)) {
            subPath = task.mFileName;
        } else {
            subPath = task.mSubDir + File.separator + task.mFileName;
        }
        if (task.mFileSaveToPublicStorage) {
            request.setDestinationInExternalPublicDir(task.mDirType, subPath);
        } else {
            request.setDestinationInExternalFilesDir(mContext, task.mDirType, subPath);
        }
        task.mDownloadId = mDownloadManager.enqueue(request);
        mTasks.add(task);
        L.GL.d("add download task：%s", Task.toString(mContext, task));
        checkMonitorDownloadStatus();
    }

    public void removeTask(final Task task, final boolean removeFile) {
        mBackHandler.post(() -> {
            synchronized (mTasks) {
                doRemoveTask(task, removeFile);
            }
        });
    }

    private void doRemoveTask(Task task, boolean removeFile) {
        for (Task t : mTasks) {
            if (t.getDownloadId() == task.getDownloadId()) {
                mTasks.remove(t);
                if (removeFile) {
                    mDownloadManager.remove(t.getDownloadId());
                }
                L.GL.d("remove download task：%s", Task.toString(mContext, t));
                break;
            }
        }
    }

    private void checkMonitorDownloadStatus() {
        mBackHandler.removeCallbacks(mCheckDownloadStatusRunn);
        if (mTasks.isEmpty()) {
            L.GL.w("no download task exist, not loop check downlaod status");
        } else {
            mBackHandler.postDelayed(mCheckDownloadStatusRunn, 1000);
        }
    }

    private final Runnable mCheckDownloadStatusRunn = new Runnable() {
        @Override
        public void run() {
            synchronized (mTasks) {
                List<Task> tasksSuccess = new ArrayList<>();
                List<Task> tasksFailed = new ArrayList<>();
                DownloadManager.Query query = new DownloadManager.Query();
                for (Task task : mTasks) {
                    query.setFilterById(task.getDownloadId());
                    Cursor cursor = mDownloadManager.query(query);
                    if (cursor.moveToFirst()) {
                        int status = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS));
                        int reason;
                        switch (status) {
                            case DownloadManager.STATUS_PAUSED:
                                reason = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_REASON));
                                L.GL.w("download paused:%s, reason:%d", Task.toString(mContext, task), reason);
                            case DownloadManager.STATUS_PENDING:
                                L.GL.w("download delayed:%s", Task.toString(mContext, task));
                            case DownloadManager.STATUS_RUNNING:
                                int soFar =
                                        cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                                int total =
                                        cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                                L.GL.v("downloading:%s,progress:%d/%d",
                                        Task.toString(mContext, task),
                                        soFar,
                                        total);
                                notifyListener(new Status.Progress(task, soFar, total));
                                break;
                            case DownloadManager.STATUS_SUCCESSFUL:
                                String destinationUri =
                                        cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI));
                                task.setFileRealDestinationUri(destinationUri);
                                L.GL.i("download success:%s", Task.toString(mContext, task));
                                tasksSuccess.add(task);
                                notifyListener(new Status.Success(task));
                                break;
                            case DownloadManager.STATUS_FAILED:
                                reason = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_REASON));
                                L.GL.e("download failed:%s, reason:%d", Task.toString(mContext, task), reason);
                                tasksFailed.add(task);
                                notifyListener(new Status.Failed(task));
                                break;
                        }
                    }
                }
                for (Task task : tasksSuccess) {
                    doRemoveTask(task, false);
                }
                for (Task task : tasksFailed) {
                    doRemoveTask(task, true);
                }
                checkMonitorDownloadStatus();
            }
        }
    };

    public static class Task {

        public static Task createInstance(String downloadUrl, String subDir, String fileName) {
            return new Task(downloadUrl,
                    Environment.DIRECTORY_DOWNLOADS, subDir,
                    fileName, false, true);
        }

        /**
         * 下载id由DownloadManager生成
         */
        private long mDownloadId;
        /**
         * 下载地址
         */
        private String mDownloadUrl;
        /**
         * true,下载的文件存储在公共文件夹里面（此时mDirType只能是系统支持的那几个）；false,下载的文件存储在app专属的文件夹里面
         */
        private boolean mFileSaveToPublicStorage;
        /**
         * 文件夹类型
         */
        private String mDirType;
        /**
         * 子目录
         */
        private String mSubDir;
        /**
         * 保存文件的文件名
         */
        private String mFileName;
        /**
         * 是否显示在通知栏
         */
        private boolean mShowNotification;
        /**
         * 如果存在是否重新下载（android10之后无法删除下载目录里面的文件，所以会设置一个新的文件名）
         */
        private boolean mReDownloadWhenExist;
        /**
         * 文件如果已经存在，重复下载文件名字可能会修改，这个变量保存的是最终存储的位置
         */
        private String mFileRealDestinationUri;

        private int mType;
        private Object mExtra;

        public Task(String downloadUrl,
                    String dirType,
                    String subDir,
                    String fileToSaveName,
                    boolean showNotification,
                    boolean reDownloadWhenExist) {
            mDownloadUrl = downloadUrl;
            mDirType = dirType;
            mSubDir = subDir;
            mFileName = fileToSaveName;
            mShowNotification = showNotification;
            mReDownloadWhenExist = reDownloadWhenExist;
        }

        public long getDownloadId() {
            return mDownloadId;
        }

        public String getDownloadUrl() {
            return mDownloadUrl;
        }

        public String getDirType() {
            return mDirType;
        }

        public String getFileName() {
            return mFileName;
        }

        public void setFileName(String fileName) {
            mFileName = fileName;
        }

        public boolean isShowNotification() {
            return mShowNotification;
        }

        public boolean isReDownloadWhenExist() {
            return mReDownloadWhenExist;
        }

        public String getSubDir() {
            return mSubDir;
        }

        public int getType() {
            return mType;
        }

        public Task setType(int type) {
            mType = type;
            return this;
        }

        public Object getExtra() {
            return mExtra;
        }

        public Task setExtra(Object extra) {
            mExtra = extra;
            return this;
        }

        public void setFileRealDestinationUri(String fileRealDestinationUri) {
            mFileRealDestinationUri = fileRealDestinationUri;
        }

        public String getFileRealDestinationUri() {
            return mFileRealDestinationUri;
        }

        public boolean isFileSaveToPublicStorage() {
            return mFileSaveToPublicStorage;
        }

        public void setFileSaveToPublicStorage(boolean fileSaveToPublicStorage) {
            mFileSaveToPublicStorage = fileSaveToPublicStorage;
        }

        public String getFileTargetDestination(Context context) {
            String subPath = (TextUtils.isEmpty(mSubDir) ? "" : (mSubDir + File.separator)) + mFileName;
            if (mFileSaveToPublicStorage) {
                return Environment.getExternalStoragePublicDirectory(mDirType) + File.separator + subPath;
            } else {
                return context.getExternalFilesDir(mDirType).getAbsolutePath() + File.separator + subPath;
            }
        }

        public static String toString(Context context, Task task) {
            StringBuilder sb = new StringBuilder();
            sb.append("id=").append(task.mDownloadId);
            sb.append(",").append("url=").append(task.mDownloadUrl);
            sb.append(",").append("target=").append(task.getFileTargetDestination(context));
            if (!TextUtils.isEmpty(task.mFileRealDestinationUri)) {
                sb.append(",").append("real=").append(task.mFileRealDestinationUri);
            }
            return sb.toString();
        }
    }

    public static class Status {

        private Task mTask;

        public Status(Task task) {
            mTask = task;
        }

        public static class Progress extends Status {

            private int mDownloadSize;
            private int mTotalSize;

            public Progress(Task task, int downloadSize, int totalSize) {
                super(task);
                mDownloadSize = downloadSize;
                mTotalSize = totalSize;
            }

            public int getDownloadSize() {
                return mDownloadSize;
            }

            public int getTotalSize() {
                return mTotalSize;
            }
        }

        public static class Success extends Status {

            public Success(Task task) {
                super(task);
            }
        }

        public static class Failed extends Status {

            public Failed(Task task) {
                super(task);
            }
        }

        public static class Existed extends Status {

            public Existed(Task task) {
                super(task);
            }
        }

        public Task getTask() {
            return mTask;
        }
    }

    public void addListener(Listener listener) {
        synchronized (mListeners) {
            if (!mListeners.contains(listener)) {
                mListeners.add(listener);
            }
        }
    }

    public void removeListener(Listener listener) {
        synchronized (mListeners) {
            mListeners.remove(listener);
        }
    }

    private void notifyListener(final Status status) {
        mUiHandler.post(() -> {
            synchronized (mListeners) {
                int s = mListeners.size();
                for (int i = 0; i < s; i++) {
                    if (mListeners.get(i).onDownload(status)) {
                        break;
                    }
                }
            }
        });
    }

    public interface Listener {
        boolean onDownload(Status status);
    }
}
