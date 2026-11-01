package me.kumavis.wishring.protocol

const val REPLY_BYTES = 12

/** How a request ended: the bytes a read returned, or the ring's non-zero status. */
sealed class RingOutcome {
    class Done(val data: ByteArray) : RingOutcome()

    data class Refused(val status: Long) : RingOutcome()
}

/**
 * Assembles the ring's answer to one request from the notifications that arrive while it is outstanding.
 * The 12-byte reply comes on the control channel, possibly split; a read's bytes come on the data channel,
 * before or after the reply. A read is complete once the reply is in and at least its data length has
 * arrived; a write is complete on its reply. Notifications carry no request id, so after a failure the
 * caller drops the link rather than guessing which request later bytes belong to.
 */
class RingExchange(val request: RingRequest) {
    private val reply = ByteArray(REPLY_BYTES)
    private var replyFill = 0
    private var dataLength = -1
    private val data = ByteArray(request.replyLimit)
    private var dataFill = 0
    private var finished = false

    fun onControl(bytes: ByteArray): RingOutcome? {
        if (finished) throw RingProtocolException("control bytes after $request completed")
        if (replyFill + bytes.size > REPLY_BYTES) throw RingProtocolException("more control bytes than one reply to $request")
        bytes.copyInto(reply, replyFill)
        replyFill += bytes.size
        if (replyFill < REPLY_BYTES) return null
        val status = reply.u32(0)
        if (status != 0L) {
            finished = true
            return RingOutcome.Refused(status)
        }
        val length = reply.u32(8)
        if (request.isWrite) {
            finished = true
            return RingOutcome.Done(ByteArray(0))
        }
        if (length > request.replyLimit) throw RingProtocolException("a reply of $length bytes to $request")
        dataLength = length.toInt()
        return completeIfReady()
    }

    fun onData(bytes: ByteArray): RingOutcome? {
        if (finished) throw RingProtocolException("data bytes after $request completed")
        val room = (if (dataLength >= 0) dataLength else data.size) - dataFill
        if (bytes.size > room) {
            if (dataLength < 0) throw RingProtocolException("more data than $request may return")
            // Past the declared length: the ring padded the last notification; keep what was declared.
        }
        val taken = minOf(bytes.size, room)
        bytes.copyInto(data, dataFill, 0, taken)
        dataFill += taken
        return completeIfReady()
    }

    private fun completeIfReady(): RingOutcome? {
        if (dataLength < 0 || dataFill < dataLength) return null
        finished = true
        return RingOutcome.Done(data.copyOf(dataLength))
    }
}
