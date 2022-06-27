package com.qbw.xdownloader_temo;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.os.Environment;

import com.qbw.l.L;
import com.qbw.xdownloader.XDownloader;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        L.GL.setEnabled(true);
        XDownloader.init(this);
        XDownloader.Task task = XDownloader.Task.createInstance("http://or5ykkw4e.bkt.clouddn.com/audios/3/2018/06/Bo9tknQok0KIhz600IN4hsg4soxQu6.mp3", "bgm", "a.mp3");
        XDownloader.getInstance().addTask(task);
        task = new XDownloader.Task("http://pandamine-test.oss-cn-hongkong.aliyuncs.com/upload/20210719/c3fd43b25aaae8f5428791558978b30a.png", Environment.DIRECTORY_DOWNLOADS,
                "", "a.png", false, false);
        XDownloader.getInstance().addTask(task);


        /*XDownloader.Task task = XDownloader.Task.createInstance("http://or5ykkw4e.bkt.clouddn.com/audios/3/2018/06/Bo9tknQok0KIhz600IN4hsg4soxQu6.mp3", getString(R.string.app_name), "a.mp3");
        task.setFileSaveToPublicStorage(true);
        XDownloader.getInstance().addTask(task);
        task = new XDownloader.Task("http://pandamine-test.oss-cn-hongkong.aliyuncs.com/upload/20210719/c3fd43b25aaae8f5428791558978b30a.png", Environment.DIRECTORY_DOWNLOADS,
                getString(R.string.app_name), "a.png", false, false);
        task.setFileSaveToPublicStorage(true);
        XDownloader.getInstance().addTask(task);*/
    }
}