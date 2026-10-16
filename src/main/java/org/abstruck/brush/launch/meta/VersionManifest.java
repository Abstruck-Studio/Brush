package org.abstruck.brush.launch.meta;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/** launcher meta 的 {@code version_manifest_v2.json} 模型（P1.2）。 */
public final class VersionManifest {

    @SerializedName("latest")
    public Latest latest;

    @SerializedName("versions")
    public List<VersionEntry> versions;

    /** latest 字段：当前最新正式版 / 快照版 id。 */
    public static final class Latest {

        @SerializedName("release")
        public String release;

        @SerializedName("snapshot")
        public String snapshot;
    }

    /** 单个版本条目。version json 的 URL 与 sha1 的唯一权威来源。 */
    public static final class VersionEntry {

        @SerializedName("id")
        public String id;

        @SerializedName("type")
        public String type;

        @SerializedName("url")
        public String url;

        @SerializedName("sha1")
        public String sha1;

        @SerializedName("time")
        public String time;

        @SerializedName("releaseTime")
        public String releaseTime;

        @SerializedName("complianceLevel")
        public int complianceLevel;
    }
}
