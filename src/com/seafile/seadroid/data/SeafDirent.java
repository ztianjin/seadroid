package com.seafile.seadroid.data;

import org.json.JSONException;
import org.json.JSONObject;

import com.seafile.seadroid.R;
import com.seafile.seadroid.Utils;

import android.util.Log;

public class SeafDirent implements SeafItem {

    public enum DirentType { DIR, FILE };
    
    public String id;
    public DirentType type;
    public String name;
    public long size;    // size of file, 0 if type is dir
    
    
    static SeafDirent fromJson(JSONObject obj) {
        SeafDirent dirent = new SeafDirent();
        try {
            dirent.id = obj.getString("id");
            dirent.name = obj.getString("name");
            String type = obj.getString("type");
            if (type.equals("file")) {
                dirent.type = DirentType.FILE;
                dirent.size = obj.getLong("size");
            } else
                dirent.type = DirentType.DIR;
            return dirent;
        } catch (JSONException e) {
            Log.d("SeafDirent", e.getMessage());
            return null;
        }
    }
    
    public boolean isDir() {
        return (type == DirentType.DIR);
    }

    @Override
    public String getTitle() {
        return name;
    }

    @Override
    public String getSubtitle() {
        if (isDir())
            return "";
        return Utils.readableFileSize(size);
    }

    @Override
    public int getIcon() {
        if (isDir())
            return R.drawable.folder;
        return Utils.getFileIcon(name);
    }
    
}
