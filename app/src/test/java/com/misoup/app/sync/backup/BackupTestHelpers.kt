package com.misoup.app.sync.backup

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/** 测试用文件流 SAF 抽象实现（模块 09 T06/T07/T08） */
class FileSink(val target: File) : BackupSink {
    override fun openOutput() = FileOutputStream(target)
}

class FileSource(val file: File) : BackupSource {
    override fun openInput() = if (file.isFile) FileInputStream(file) else null
}
