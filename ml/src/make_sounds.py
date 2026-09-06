"""
Synthesise the built-in soothing sounds.

Generated rather than sourced, for two reasons: no licensing question at a
public demo, and each one can be built to a spec rather than to whatever a
stock library happened to contain.

The specs come from what is actually known about infant settling -- broadband
noise resembling intrauterine sound, a shush at roughly the rhythm a caregiver
produces naturally, and a heartbeat at a resting maternal rate. None of that
makes this a medical intervention; it makes it a better white-noise track.
"""

import numpy as np
import soundfile as sf

from config import MODELS, DATA, SAMPLE_RATE

ASSETS = MODELS.parents[1] / "android" / "app" / "src" / "main" / "assets"

OUT = MODELS.parents[1] / "android" / "app" / "src" / "main" / "assets"
# 22.05 kHz is ample for these three: the heartbeat is entirely below 320 Hz and
# the shush is a band around 2.6 kHz, so the higher rate only doubled the APK.
SR = 22_050
SECONDS = 30
PEAK = 0.42                      # played a metre from an infant; leave headroom


def _fade(x: np.ndarray, ms: int = 400) -> np.ndarray:
    n = int(SR * ms / 1000)
    ramp = np.linspace(0.0, 1.0, n)
    x[:n] *= ramp
    x[-n:] *= ramp[::-1]
    return x


def _loopable(x: np.ndarray, ms: int = 250) -> np.ndarray:
    """Crossfade the tail into the head so looping has no audible seam."""
    n = int(SR * ms / 1000)
    ramp = np.linspace(0.0, 1.0, n)
    head, tail = x[:n].copy(), x[-n:].copy()
    x[:n] = head * ramp + tail * (1 - ramp)
    return x[:-n]


def _normalise(x: np.ndarray) -> np.ndarray:
    peak = float(np.max(np.abs(x))) or 1.0
    return (x / peak * PEAK).astype(np.float32)


def pink_noise(n: int, rng) -> np.ndarray:
    """1/f noise via spectral shaping -- closer to womb sound than white."""
    white = rng.standard_normal(n)
    spectrum = np.fft.rfft(white)
    freqs = np.fft.rfftfreq(n, 1 / SR)
    freqs[0] = freqs[1]
    spectrum /= np.sqrt(freqs)
    return np.fft.irfft(spectrum, n)


def make_white_noise(rng) -> np.ndarray:
    n = SR * SECONDS
    x = pink_noise(n, rng)
    # Roll off above 4 kHz. Infant hearing is sensitive up there and the harsh
    # top end is what makes cheap white-noise tracks unpleasant to sit next to.
    spectrum = np.fft.rfft(x)
    freqs = np.fft.rfftfreq(n, 1 / SR)
    spectrum *= 1.0 / (1.0 + (freqs / 4000.0) ** 2)
    x = np.fft.irfft(spectrum, n)
    return _normalise(_fade(x))


def make_shush(rng) -> np.ndarray:
    """Band-passed noise pulsed at ~0.9 Hz, the rate a caregiver shushes at."""
    n = SR * SECONDS
    x = pink_noise(n, rng)

    spectrum = np.fft.rfft(x)
    freqs = np.fft.rfftfreq(n, 1 / SR)
    band = np.exp(-0.5 * ((np.log(np.maximum(freqs, 1)) - np.log(2600)) / 0.55) ** 2)
    x = np.fft.irfft(spectrum * band, n)

    t = np.arange(n) / SR
    rate = 0.9
    phase = (t * rate) % 1.0
    # Fast attack, slow decay -- a shush, not a siren.
    envelope = np.where(phase < 0.22,
                        phase / 0.22,
                        np.exp(-(phase - 0.22) * 4.5))
    return _normalise(_fade(x * (0.25 + 0.75 * envelope)))


def make_heartbeat(rng) -> np.ndarray:
    """Lub-dub at 72 bpm with a little natural variation."""
    n = SR * SECONDS
    x = np.zeros(n)
    bpm = 72.0
    beat = SR * 60.0 / bpm

    def thump(start: int, amp: float, freq: float, decay: float, dur: float):
        length = int(SR * dur)
        if start + length >= n:
            return
        t = np.arange(length) / SR
        wave = np.sin(2 * np.pi * freq * t) * np.exp(-t * decay)
        wave += 0.4 * rng.standard_normal(length) * np.exp(-t * decay * 2.2)
        x[start:start + length] += wave * amp

    i = 0.0
    while i < n - SR:
        jitter = rng.normal(0, beat * 0.015)
        s1 = int(i + jitter)
        thump(s1, 1.00, 52.0, 26.0, 0.16)                      # lub
        thump(s1 + int(beat * 0.30), 0.62, 68.0, 32.0, 0.12)   # dub
        i += beat

    spectrum = np.fft.rfft(x)
    freqs = np.fft.rfftfreq(n, 1 / SR)
    spectrum *= 1.0 / (1.0 + (freqs / 320.0) ** 2)             # muffled, in utero
    x = np.fft.irfft(spectrum, n)
    return _normalise(_fade(x))


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    rng = np.random.default_rng(7)

    for name, fn in (("soothe_white_noise", make_white_noise),
                     ("soothe_shush", make_shush),
                     ("soothe_heartbeat", make_heartbeat)):
        audio = _loopable(fn(rng))
        path = OUT / f"{name}.wav"
        sf.write(str(path), audio, SR, subtype="PCM_16")
        print(f"  {path.name:28s} {path.stat().st_size / 1024:7.1f} KB  "
              f"{len(audio) / SR:.1f}s  peak {np.max(np.abs(audio)):.2f}")

    # A synthetic cry for the built-in self-test. Deliberately not a real
    # recording: it must not be mistaken for validation data, and it lets the
    # self-test ship without a licensing question.
    n = SR * 4
    t = np.arange(n) / SR
    f0 = 420 + 110 * np.sin(2 * np.pi * 0.55 * t) + 40 * np.sin(2 * np.pi * 3.1 * t)
    cry = np.zeros(n)
    for harmonic, amp in enumerate([1.0, 0.62, 0.42, 0.28, 0.18, 0.11], start=1):
        cry += amp * np.sin(2 * np.pi * np.cumsum(f0 * harmonic) / SR)
    burst = (np.sin(2 * np.pi * 0.55 * t - 1.2) > -0.35).astype(float)
    burst = np.convolve(burst, np.hanning(2205) / np.hanning(2205).sum(), mode="same")
    cry = _normalise(_fade(cry * burst * (0.8 + 0.2 * rng.standard_normal(n) * 0.1)))
    sf.write(str(OUT / "selftest_cry.wav"), cry, SR, subtype="PCM_16")
    print(f"  selftest_cry.wav             "
          f"{(OUT / 'selftest_cry.wav').stat().st_size / 1024:7.1f} KB  4.0s")


if __name__ == "__main__":
    main()


# --------------------------------------------------------------- demo cry

def build_demo_cry(out_path=None):
    """
    The clip the demo and the self-test play.

    Assembled from real corpus recordings rather than synthesised, because the
    point of the demo is to show the detector responding to an actual infant --
    and because a synthetic tone is obvious to anyone watching the video.

    Two clips from two different infants are used, chosen by scoring the whole
    corpus with the shipped detector and keeping the ones where *every* window
    is confidently a cry. Both sit at a fundamental of 360-470 Hz, which is
    where infant cry lives.

    The structure is deliberate: a moment of room tone so the detector has to
    cross its threshold rather than starting on the far side of it, then bursts
    separated by breath gaps, rising in level across the clip. That gives the
    envelope chart something true to draw and makes the RISING trend -- which
    drives escalation -- visible on screen instead of theoretical.
    """
    import librosa
    import numpy as np
    import soundfile as sf
    from config import SAMPLE_RATE, DATA

    corpus = (DATA / "donateacry-corpus" /
              "donateacry_corpus_cleaned_and_updated_data")
    picks = [
        corpus / "hungry" /
        "e4051e62-d21d-4bb8-a235-fd7e859ad787-1430739911506-1.7-m-04-hu.wav",
        corpus / "hungry" /
        "85FAB169-4DCC-406D-AC93-2F6A8D797DAB-1437632301539-1.7-m-04-hu.wav",
    ]
    picks = [p for p in picks if p.exists()]
    if not picks:
        picks = sorted(corpus.glob("hungry/*.wav"))[:2]

    def bursts(path):
        """Split one recording into its cry bursts, dropping the gaps."""
        y, _ = librosa.load(str(path), sr=SAMPLE_RATE)
        y = y / (np.abs(y).max() + 1e-9)
        # 30 dB below peak is a breath, not a cry.
        intervals = librosa.effects.split(y, top_db=30,
                                          frame_length=1024, hop_length=256)
        return [y[a:b] for a, b in intervals if (b - a) > SAMPLE_RATE * 0.25]

    pool = [b for p in picks for b in bursts(p)]
    if not pool:
        raise RuntimeError("no cry bursts found in the corpus")

    breath = np.zeros(int(SAMPLE_RATE * 0.32), dtype=np.float32)
    parts = [np.zeros(int(SAMPLE_RATE * 1.2), dtype=np.float32)]

    target = SAMPLE_RATE * 12
    length = len(parts[0])
    i = 0
    while length < target:
        burst = pool[i % len(pool)]
        # Ramp from -18 dBFS to -8 dBFS across the clip, so the trend reads as
        # rising rather than flat.
        progress = min(1.0, length / target)
        gain = 10 ** ((-18 + 10 * progress) / 20)
        shaped = burst * (gain / (np.abs(burst).max() + 1e-9))
        # 12 ms cosine edges: a hard cut clicks, and a click is broadband
        # energy the frontend sees as a transient.
        edge = int(SAMPLE_RATE * 0.012)
        window = np.ones(len(shaped), dtype=np.float32)
        window[:edge] = np.linspace(0, 1, edge)
        window[-edge:] = np.linspace(1, 0, edge)
        parts.append((shaped * window).astype(np.float32))
        parts.append(breath)
        length += len(shaped) + len(breath)
        i += 1

    clip = np.concatenate(parts)
    # A whisper of room tone under everything, so the silence gate sees a real
    # room rather than digital zero.
    clip += np.random.RandomState(7).normal(0, 10 ** (-62 / 20), len(clip))
    clip = np.clip(clip, -1.0, 1.0).astype(np.float32)

    out_path = out_path or (ASSETS / "demo_cry.wav")
    sf.write(str(out_path), clip, SAMPLE_RATE, subtype="PCM_16")
    print(f"  -> {out_path.name}  {len(clip)/SAMPLE_RATE:.1f}s  "
          f"peak {20*np.log10(np.abs(clip).max()):.1f} dBFS")
    return out_path


if __name__ == "__main__" and "--demo-cry" in __import__("sys").argv:
    build_demo_cry()
