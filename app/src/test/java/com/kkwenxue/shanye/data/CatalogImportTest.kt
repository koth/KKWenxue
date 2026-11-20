package com.kkwenxue.shanye.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

class CatalogImportTest {
    @Test fun contentFailuresIdentifyTheStageWithoutShowingRawWorkerErrors() {
        val failed = CatalogContentTask("task-1", "failed", "<urlopen error [Errno 104] Connection reset by peer>")
        assertEquals("服务器下载书源正文时连接中断，请稍后重试", failed.copy(kind = "content.fetch").failureMessage)
        assertEquals("服务器未能打包正文，请稍后重试", failed.copy(kind = "content.serve").failureMessage)
        assertEquals("服务器未能准备正文，请稍后重试", failed.copy(message = "服务器合成失败").failureMessage)
        assertEquals(null, failed.copy(state = "running").failureMessage)
    }

    @Test fun onlyRelayIsContactedAnd202IsNotWrittenAsABook() {
        ServerSocket(0, 5, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 5_000
            val requests = FutureTask {
                listOf("202 Accepted" to "正在上传", "200 OK" to "正文包").map { (status, message) ->
                    server.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val input = socket.getInputStream().bufferedReader()
                        val request = input.readLine()
                        while (!input.readLine().isNullOrEmpty()) {}
                        val body = message.toByteArray()
                        val response = socket.getOutputStream()
                        response.write("HTTP/1.1 $status\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        response.write(body)
                        response.flush()
                        request
                    }
                }
            }
            Thread(requests, "catalog-test").apply { isDaemon = true; start() }
            val target = File.createTempFile("book-test-", ".zip")
            try {
                val client = CatalogClient("http://127.0.0.1:${server.localPort}")
                val task = CatalogContentTask("task-1", "completed", downloadUrl = "https://unused.invalid")
                assertFalse(client.download(task, target))
                assertEquals(0L, target.length())
                assertTrue(client.download(task, target))
                assertEquals("正文包", target.readText())
                assertEquals(List(2) { "GET /v1/catalog/content/task-1/download HTTP/1.1" },
                    requests.get(5, TimeUnit.SECONDS))
            } finally { target.delete() }
        }
    }

    @Test fun usesCatalogMetadataAndRecognizesServerChapterHeadings() {
        val source = CatalogBook(7, "测试书", "测试作者", "文学", "", 0, 2, "", "")
        val text = "第1章开场\n段落甲。\n\n第2章：后来\n段落乙。\n".toByteArray()
        val book = BookImporter.parseCatalogText(source, text)
        assertEquals("测试书", book.title)
        assertEquals("测试作者", book.author)
        assertEquals(listOf("第1章开场", "第2章：后来"), book.chapters.map { it.title })
        assertEquals(listOf("段落甲。", "段落乙。"), book.chapters.map { it.body })
        assertEquals("online:catalog:7", book.id)
        assertTrue(book.chapters.all { it.id.startsWith("online:catalog:7:chapter:sha256:") })
        val inserted = BookImporter.parseCatalogText(
            source, "序章\n这里是序章正文。\n\n${text.decodeToString()}".toByteArray()
        )
        assertEquals(book.chapters.map { it.id }, inserted.chapters.drop(1).map { it.id })
        assertEquals(7L, book.remoteBookId)
    }

}
