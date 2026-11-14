package com.kkorpse.less.data

/**
 * MediaStore 里的一个相册（文件夹 / bucket）。
 *
 * @param id MediaStore.MediaColumns.BUCKET_ID，选择相册时用它过滤
 * @param name 相册显示名，通常是文件夹名
 * @param count 相册里的照片数量
 */
data class MediaBucket(
    val id: String,
    val name: String,
    val count: Int
)
