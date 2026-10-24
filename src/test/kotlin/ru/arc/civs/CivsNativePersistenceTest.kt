package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.CompletionException

class CivsNativePersistenceTest : FunSpec({
    test("native IO failures remain retryable through reflection and future wrappers while mismatches fail closed") {
        val disk = IOException("force failed")
        val retry = civsNativePersistenceFailure(CompletionException(InvocationTargetException(disk)))
        (retry is CivsNativePersistenceRetry) shouldBe true
        retry.cause shouldBe disk
        val mismatch = IllegalStateException("Removed Item is still on disk")
        civsNativePersistenceFailure(CompletionException(InvocationTargetException(mismatch))) shouldBe mismatch
        val timeout = CivsNativePersistenceRetry("Native persistence timed out")
        civsNativePersistenceFailure(CompletionException(timeout)) shouldBe timeout
    }

    fun region(header: Int = 0x201, length: Int = 2, compression: Int = 2,
        check: (FileChannel) -> Unit) {
        val file = Files.createTempFile("civs-region-proof", ".mca")
        try {
            FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel ->
                val contents = ByteBuffer.allocate(8192 + 6)
                contents.putInt(0, header)
                contents.putInt(8192, length)
                contents.put(8196, compression.toByte())
                channel.write(contents)
                channel.force(true)
                check(channel)
            }
        } finally { Files.deleteIfExists(file) }
    }

    test("readback accepts a complete internal record and a genuinely empty header") {
        region { channel ->
            civsVerifyRegionHeader(channel, 0, 0x201)
            civsVerifyRegionHeader(channel, 1, 0)
        }
    }

    test("cached new offset after failed native header write is never acknowledged") {
        region(header = 0x201) { channel ->
            shouldThrow<IllegalStateException> { civsVerifyRegionHeader(channel, 0, 0x301) }
        }
    }

    test("cached deletion after failed native header write is never acknowledged") {
        region(header = 0x201) { channel ->
            shouldThrow<IllegalStateException> { civsVerifyRegionHeader(channel, 0, 0) }
        }
    }

    test("truncated header and partially written region payload fail closed") {
        region(length = 128) { channel ->
            shouldThrow<IllegalStateException> { civsVerifyRegionHeader(channel, 0, 0x201) }
            channel.truncate(2)
            shouldThrow<IllegalStateException> { civsVerifyRegionHeader(channel, 0, 0x201) }
        }
    }

    test("external records cannot borrow the internal region force acknowledgement") {
        region(compression = 130) { channel ->
            shouldThrow<IllegalStateException> { civsVerifyRegionHeader(channel, 0, 0x201) }
        }
    }

    test("invalid sector locations or lengths cannot masquerade as native state") {
        region(header = 0x101) { channel ->
            shouldThrow<IllegalStateException> { civsVerifyRegionHeader(channel, 0, 0x101) }
        }
        region(length = 4096) { channel ->
            shouldThrow<IllegalStateException> { civsVerifyRegionHeader(channel, 0, 0x201) }
        }
    }
})
