using CoulombMppt.Data;
using CoulombMppt.Data.BatteryModel;
using Xunit;

namespace CoulombMppt.Tests;

// Synthetic-recovery test: build a frame stream from a KNOWN OCV curve, KNOWN R,
// and KNOWN overnight bus draw, push it through the same LearnerCore the live
// path uses, and assert the learner recovers the planted parameters. All
// timestamps are explicit — no wall-clock dependency in the math.
public sealed class LearnerReplayTests
{
    // --- the ground truth we plant -----------------------------------------
    private const double KnownR        = 0.10;   // 100 mΩ pack
    private const double KnownBaseA    = 6.0;    // overnight inverter+house draw
    private const double CapacityAh    = 200.0;

    // A sloped NMC OCV curve (V at SoC%). Linear interp matches InterpOcv.
    private static double OcvAt(double soc) => 46.0 + 0.085 * soc;   // 46 V @0% → 54.5 V @100%

    private static LearnerCore NewCore() => new(
        mac: "AA:BB:CC:DD:EE:FF",
        socBinWidthPct: 10,
        tempBinWidthC: 10,
        restDvDtThresholdMv: 5.0,
        restSettleSec: 60,
        rTransientMinDeltaA: 1.0,
        rTransientMaxStepMs: 2000,
        quietStartHour: 22,
        quietEndHour: 6) { CapacityAh = CapacityAh };

    // A timestamp at a given local hour on a fixed day, plus an offset in ms.
    private static long LocalHour(int hour, long offsetMs = 0)
    {
        var local = new DateTimeOffset(2026, 1, 15, hour, 0, 0, TimeZoneInfo.Local.GetUtcOffset(
            new DateTime(2026, 1, 15, hour, 0, 0, DateTimeKind.Unspecified)));
        return local.ToUnixTimeMilliseconds() + offsetMs;
    }

    // Feed a settled rest period at a known SoC: flat terminal V = OCV(soc). The
    // SocHint stands in for the controller's coarse V→SoC estimate, which the
    // learner uses only to bootstrap its coulomb anchor.
    private static void FeedRest(LearnerCore core, double soc, long startMs, int seconds)
    {
        double v = OcvAt(soc);   // at rest I_batt=0 ⇒ V = OCV
        for (int s = 0; s <= seconds; s++)
            core.Process(new LearnerFrame(startMs + s * 1000L, v, 0, 0, 25.0, LinkReady: true, SocHint: soc));
    }

    // Feed a fast PV step: charge current jumps by dI within one second.
    // Terminal V steps by R·dI (OCV ~constant across the sub-second step).
    private static long FeedPvStep(LearnerCore core, double soc, long startMs, double fromA, double toA)
    {
        double ocv = OcvAt(soc);
        // settle a moment at fromA
        core.Process(new LearnerFrame(startMs,        ocv + KnownR * fromA, fromA, 0, 25.0, true, SocHint: soc));
        // the step (1 s later)
        core.Process(new LearnerFrame(startMs + 1000, ocv + KnownR * toA,   toA,   0, 25.0, true, SocHint: soc));
        return startMs + 1000;
    }

    // Feed overnight quiet-discharge frames: PV=0, battery sagging under a steady
    // bus draw. V = OCV(soc) − KnownBaseA·R. SoC essentially flat over the window.
    private static void FeedQuietNight(LearnerCore core, double soc, long startMs, int seconds)
    {
        double v = OcvAt(soc) - KnownBaseA * KnownR;
        for (int s = 0; s <= seconds; s++)
            core.Process(new LearnerFrame(startMs + s * 1000L, v, 0, 0, 25.0, LinkReady: true, SocHint: soc));
    }

    [Fact]
    public void Recovers_R_Ocv_And_Baseload_FromSyntheticStream()
    {
        var core = NewCore();

        // 1) Rest periods at several SoCs → builds the OCV table + anchors SoC.
        //    Daytime hours so they don't also feed the night baseload.
        FeedRest(core, 30, LocalHour(10), 90);
        FeedRest(core, 50, LocalHour(12), 90);
        FeedRest(core, 70, LocalHour(14), 90);
        FeedRest(core, 90, LocalHour(16), 90);

        // 2) Several fast PV steps at varying SoC → R candidates.
        FeedPvStep(core, 50, LocalHour(11, 0),      2,  12);
        FeedPvStep(core, 50, LocalHour(11, 10_000), 1,  9);
        FeedPvStep(core, 50, LocalHour(11, 20_000), 3,  15);
        FeedPvStep(core, 50, LocalHour(11, 30_000), 0,  8);
        FeedPvStep(core, 50, LocalHour(11, 40_000), 5,  14);
        FeedPvStep(core, 50, LocalHour(11, 50_000), 2,  11);

        // 3) Overnight quiet discharge at ~70% SoC → baseload band.
        //    Re-establish a rest anchor at 70% right before so SoC is correct,
        //    then run the night window (02:00 local is inside 22..6).
        FeedRest(core, 70, LocalHour(1), 90);
        FeedQuietNight(core, 70, LocalHour(2), 600);   // 10 min of 1 Hz samples

        var rec = core.BuildRecord(updatedMs: LocalHour(6), backfillThroughMs: 0);

        // --- R: global slope recovers the planted 100 mΩ within tolerance ----
        Assert.True(rec.R.HasResistance, "expected a global resistance");
        Assert.Equal(KnownR, rec.R.RGlobalOhms, precision: 3);

        // --- OCV: ≥2 monotonically-increasing points spanning the rested SoCs -
        Assert.True(rec.Ocv.Count >= 2, $"expected ≥2 OCV points, got {rec.Ocv.Count}");
        for (int i = 1; i < rec.Ocv.Count; i++)
        {
            Assert.True(rec.Ocv.Points[i].SocPct > rec.Ocv.Points[i - 1].SocPct, "SoC must increase");
            Assert.True(rec.Ocv.Points[i].Ocv    > rec.Ocv.Points[i - 1].Ocv,    "OCV must increase (NMC)");
        }
        // Interpolated OCV at 50% lands near the planted curve.
        double ocv50 = TheveninMath.InterpOcv(rec.Ocv, 50);
        Assert.Equal(OcvAt(50), ocv50, precision: 0);   // within ~1 V

        // --- Baseload: P50 lands on the planted ~6 A draw -------------------
        Assert.True(rec.Baseload.SampleCount > 0, "expected baseload samples");
        Assert.InRange(rec.Baseload.P50A, KnownBaseA - 1.0, KnownBaseA + 1.0);
    }

    [Fact]
    public void NoModel_NoCurrent_StillBuildsOcvFromRest()
    {
        // Even with zero PV activity (so no R), rest periods alone yield an OCV
        // table — the path that 30 s history backfill mostly exercises.
        var core = NewCore();
        FeedRest(core, 20, LocalHour(9),  90);
        FeedRest(core, 60, LocalHour(13), 90);

        var rec = core.BuildRecord(LocalHour(18), 0);
        Assert.True(rec.Ocv.Count >= 2);
        Assert.False(rec.R.HasResistance);   // no transients ⇒ no R, by design
    }

    [Fact]
    public void Confidence_RisesWithCoverage()
    {
        var core = NewCore();
        var empty = core.BuildRecord(0, 0);
        Assert.Equal(0, empty.ConfidencePct);   // nothing learned yet

        // Full synthetic run should produce a non-zero confidence.
        FeedRest(core, 20, LocalHour(9),  90);
        FeedRest(core, 80, LocalHour(15), 90);
        for (int i = 0; i < 8; i++)
            FeedPvStep(core, 50, LocalHour(11, i * 5_000), 1, 11);
        FeedRest(core, 70, LocalHour(1), 90);
        FeedQuietNight(core, 70, LocalHour(3), 600);

        var rec = core.BuildRecord(LocalHour(6), 0);
        Assert.True(rec.ConfidencePct > 0, $"expected confidence > 0, got {rec.ConfidencePct}");
    }
}
