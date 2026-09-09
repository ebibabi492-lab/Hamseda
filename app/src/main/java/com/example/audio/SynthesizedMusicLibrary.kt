package com.example.audio

import com.example.model.Track
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

object SynthesizedMusicLibrary {

    val builtInTracks = listOf(
        Track(
            id = "synth_neon_sunset",
            title = "غروب نئونی (Neon Sunset)",
            artist = "استودیو هم‌صدا",
            durationMs = 28000L,
            genre = "سینث‌ویو / الکترونیک",
            addedBy = "سیستم هم‌صدا",
            votes = 12
        ),
        Track(
            id = "synth_ambient_piano",
            title = "نوای آرامش شب (Calm Night)",
            artist = "استودیو هم‌صدا",
            durationMs = 24000L,
            genre = "پیانو امبینت",
            addedBy = "سیستم هم‌صدا",
            votes = 8
        ),
        Track(
            id = "synth_party_groove",
            title = "ریتم هماهنگ مهمانی (Sync Party)",
            artist = "دی‌جی پارسی",
            durationMs = 26000L,
            genre = "هاوس و فانک",
            addedBy = "کاربر مهمان",
            votes = 15
        ),
        Track(
            id = "synth_acoustic_breeze",
            title = "نسیم کوهستان (Mountain Breeze)",
            artist = "استودیو هم‌صدا",
            durationMs = 22000L,
            genre = "آکوستیک آرامش‌بخش",
            addedBy = "سیستم هم‌صدا",
            votes = 6
        )
    )

    private val cachedWavs = mutableMapOf<String, ByteArray>()

    fun getWavDataForTrack(trackId: String): ByteArray {
        cachedWavs[trackId]?.let { return it }
        val wav = when (trackId) {
            "synth_ambient_piano" -> generateAmbientPianoTrack()
            "synth_party_groove" -> generatePartyGrooveTrack()
            "synth_acoustic_breeze" -> generateAcousticTrack()
            else -> generateNeonSunsetTrack()
        }
        cachedWavs[trackId] = wav
        return wav
    }

    private fun generateNeonSunsetTrack(): ByteArray {
        val sampleRate = 44100
        val durationSeconds = 28
        val totalSamples = sampleRate * durationSeconds
        val pcm = ShortArray(totalSamples)

        // Chords: Am, F, C, G
        val chords = listOf(
            listOf(220.0, 261.63, 329.63), // Am
            listOf(174.61, 220.0, 261.63), // F
            listOf(261.63, 329.63, 392.0), // C
            listOf(196.0, 246.94, 293.66)  // G
        )
        val chordDuration = sampleRate * 2 // 2 seconds per chord

        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate
            val chordIndex = ((i / chordDuration) % chords.size)
            val currentChord = chords[chordIndex]

            // Pad Synth (warm sawtooth + sine)
            var pad = 0.0
            for (freq in currentChord) {
                val phase = 2.0 * PI * freq * t
                pad += 0.15 * (sin(phase) + 0.3 * sin(2 * phase) + 0.15 * sin(3 * phase))
            }

            // Arpeggio Lead
            val arpNoteIndex = ((i / (sampleRate / 8)) % currentChord.size)
            val arpFreq = currentChord[arpNoteIndex] * 2.0
            val arpPhase = 2.0 * PI * arpFreq * t
            val arpEnv = exp(-((i % (sampleRate / 8)).toDouble() / (sampleRate / 12)))
            val arp = 0.25 * sin(arpPhase) * arpEnv

            // Bassline (808 style punch)
            val bassFreq = currentChord[0] / 2.0
            val bassPhase = 2.0 * PI * bassFreq * t
            val bass = 0.3 * sin(bassPhase)

            // Beat / Kick every 0.5 sec (120 BPM)
            val beatSample = i % (sampleRate / 2)
            val kickT = beatSample.toDouble() / sampleRate
            val kick = if (kickT < 0.15) {
                val kickFreq = 120.0 * exp(-kickT * 25.0) + 45.0
                0.45 * sin(2.0 * PI * kickFreq * kickT) * exp(-kickT * 18.0)
            } else 0.0

            // Hi-hat every 0.25 sec
            val hatSample = i % (sampleRate / 4)
            val hatT = hatSample.toDouble() / sampleRate
            val hat = if (hatT < 0.04) {
                val noise = (Math.random() * 2.0 - 1.0)
                0.12 * noise * exp(-hatT * 60.0)
            } else 0.0

            val mixed = (pad + arp + bass + kick + hat).coerceIn(-1.0, 1.0)
            pcm[i] = (mixed * 32767.0).toInt().toShort()
        }

        return createWavFile(pcm, sampleRate, channels = 1)
    }

    private fun generateAmbientPianoTrack(): ByteArray {
        val sampleRate = 44100
        val durationSeconds = 24
        val totalSamples = sampleRate * durationSeconds
        val pcm = ShortArray(totalSamples)

        // Melodic notes in C Major / A Minor
        val melodyNotes = listOf(
            261.63, 329.63, 392.0, 523.25, 440.0, 392.0, 329.63, 293.66,
            349.23, 440.0, 523.25, 440.0, 392.0, 329.63, 261.63, 220.0
        )
        val noteLength = sampleRate

        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate
            val noteIdx = ((i / noteLength) % melodyNotes.size)
            val freq = melodyNotes[noteIdx]

            val notePos = (i % noteLength).toDouble() / sampleRate
            val pianoEnv = exp(-notePos * 2.5)

            // Fundamental + harmonic richness
            val piano = (0.4 * sin(2 * PI * freq * t) +
                    0.25 * sin(2 * PI * freq * 2 * t) +
                    0.12 * sin(2 * PI * freq * 3 * t) +
                    0.06 * sin(2 * PI * freq * 4 * t)) * pianoEnv

            // Sub ambient drone
            val drone = 0.15 * sin(2 * PI * 130.81 * t) + 0.1 * sin(2 * PI * 196.0 * t)

            val mixed = (piano + drone).coerceIn(-1.0, 1.0)
            pcm[i] = (mixed * 32767.0).toInt().toShort()
        }

        return createWavFile(pcm, sampleRate, channels = 1)
    }

    private fun generatePartyGrooveTrack(): ByteArray {
        val sampleRate = 44100
        val durationSeconds = 26
        val totalSamples = sampleRate * durationSeconds
        val pcm = ShortArray(totalSamples)

        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate

            // Fast groove at 128 BPM (0.468 sec per beat)
            val beatInterval = (sampleRate * 60) / 128
            val beatPos = i % beatInterval
            val beatT = beatPos.toDouble() / sampleRate

            // Punchy kick
            val kick = if (beatT < 0.18) {
                val f = 150.0 * exp(-beatT * 30.0) + 40.0
                0.5 * sin(2 * PI * f * beatT) * exp(-beatT * 20.0)
            } else 0.0

            // Snare on beats 2 and 4
            val measurePos = i % (beatInterval * 4)
            val isSnareBeat = measurePos >= (beatInterval * 2) && measurePos < (beatInterval * 3)
            val snare = if (isSnareBeat && (measurePos - beatInterval * 2) < (sampleRate * 0.15)) {
                val noise = (Math.random() * 2.0 - 1.0)
                0.3 * noise * exp(-(measurePos - beatInterval * 2).toDouble() / (sampleRate * 0.05))
            } else 0.0

            // Funky Bassline
            val bassPattern = listOf(55.0, 55.0, 65.41, 73.42, 82.41, 73.42)
            val bassIdx = ((i / (beatInterval / 2)) % bassPattern.size)
            val bassFreq = bassPattern[bassIdx]
            val bassEnv = exp(-((i % (beatInterval / 2)).toDouble() / (sampleRate * 0.2)))
            val bass = 0.35 * (sin(2 * PI * bassFreq * t) + 0.2 * sin(4 * PI * bassFreq * t)) * bassEnv

            val mixed = (kick + snare + bass).coerceIn(-1.0, 1.0)
            pcm[i] = (mixed * 32767.0).toInt().toShort()
        }

        return createWavFile(pcm, sampleRate, channels = 1)
    }

    private fun generateAcousticTrack(): ByteArray {
        val sampleRate = 44100
        val durationSeconds = 22
        val totalSamples = sampleRate * durationSeconds
        val pcm = ShortArray(totalSamples)

        val strumNotes = listOf(196.0, 246.94, 293.66, 392.0, 493.88)
        val strumInterval = sampleRate * 3 / 2

        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate
            val cycleSample = i % strumInterval
            var acoustic = 0.0

            for ((idx, freq) in strumNotes.withIndex()) {
                val noteOffset = idx * (sampleRate / 40)
                if (cycleSample >= noteOffset) {
                    val notePos = (cycleSample - noteOffset).toDouble() / sampleRate
                    val decay = exp(-notePos * 3.5)
                    acoustic += 0.12 * sin(2 * PI * freq * t) * decay
                }
            }

            val mixed = acoustic.coerceIn(-1.0, 1.0)
            pcm[i] = (mixed * 32767.0).toInt().toShort()
        }

        return createWavFile(pcm, sampleRate, channels = 1)
    }

    private fun createWavFile(pcmData: ShortArray, sampleRate: Int, channels: Int): ByteArray {
        val byteRate = sampleRate * channels * 2
        val dataSize = pcmData.size * 2
        val totalSize = 36 + dataSize

        val out = ByteArrayOutputStream(44 + dataSize)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF header
        header.put('R'.code.toByte())
        header.put('I'.code.toByte())
        header.put('F'.code.toByte())
        header.put('F'.code.toByte())
        header.putInt(totalSize)
        header.put('W'.code.toByte())
        header.put('A'.code.toByte())
        header.put('V'.code.toByte())
        header.put('E'.code.toByte())

        // fmt chunk
        header.put('f'.code.toByte())
        header.put('m'.code.toByte())
        header.put('t'.code.toByte())
        header.put(' '.code.toByte())
        header.putInt(16) // Subchunk1Size (16 for PCM)
        header.putShort(1) // AudioFormat (1 = PCM)
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort((channels * 2).toShort()) // block align
        header.putShort(16) // bits per sample

        // data chunk
        header.put('d'.code.toByte())
        header.put('a'.code.toByte())
        header.put('t'.code.toByte())
        header.put('a'.code.toByte())
        header.putInt(dataSize)

        out.write(header.array())

        val dataBytes = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in pcmData) {
            dataBytes.putShort(sample)
        }
        out.write(dataBytes.array())

        return out.toByteArray()
    }
}
