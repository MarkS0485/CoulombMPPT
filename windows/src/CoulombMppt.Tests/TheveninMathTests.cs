using CoulombMppt.Data;
using Xunit;

namespace CoulombMppt.Tests;

// Pure-math guards for the Thevenin observer primitives. No wall-clock, no IO.
public sealed class TheveninMathTests
{
    [Fact]
    public void RobustSlope_RecoversKnownR_DespiteOutliers()
    {
        const double r = 0.08;   // 80 mΩ pack
        var pairs = new List<(double dI, double dV)>();
        // Clean events: dV = R·dI.
        for (int i = 1; i <= 10; i++)
            pairs.Add((dI: i, dV: r * i));
        // Outliers: an inverter step colliding with a PV edge — wildly off.
        pairs.Add((dI: 3, dV: 5.0));
        pairs.Add((dI: 4, dV: -2.0));

        double? slope = TheveninMath.RobustSlope(pairs, minAbsDi: 0.5);

        Assert.NotNull(slope);
        Assert.Equal(r, slope!.Value, precision: 6);   // median shrugs off the two outliers
    }

    [Fact]
    public void RobustSlope_ReturnsNull_WhenNoCandidateMeetsMinDi()
    {
        var pairs = new List<(double dI, double dV)> { (0.1, 0.01), (0.2, 0.02) };
        Assert.Null(TheveninMath.RobustSlope(pairs, minAbsDi: 0.5));
    }

    [Fact]
    public void InterpOcv_InvertOcv_RoundTrip_OnSlopedCurve()
    {
        var table = new OcvTable(new[]
        {
            new OcvPoint(0,   46.0, 5),
            new OcvPoint(25,  48.5, 5),
            new OcvPoint(50,  51.0, 5),
            new OcvPoint(75,  53.0, 5),
            new OcvPoint(100, 54.5, 5),
        });

        foreach (double soc in new[] { 5.0, 12.5, 37.5, 60.0, 88.0 })
        {
            double v   = TheveninMath.InterpOcv(table, soc);
            double inv = TheveninMath.InvertOcv(table, v);
            Assert.Equal(soc, inv, precision: 3);
        }
    }

    [Fact]
    public void InterpOcv_ClampsBeyondEnds()
    {
        var table = new OcvTable(new[] { new OcvPoint(10, 47.0, 1), new OcvPoint(90, 53.0, 1) });
        Assert.Equal(47.0, TheveninMath.InterpOcv(table, -50));
        Assert.Equal(53.0, TheveninMath.InterpOcv(table, 200));
        Assert.Equal(10.0, TheveninMath.InvertOcv(table, 40.0));
        Assert.Equal(90.0, TheveninMath.InvertOcv(table, 60.0));
    }

    [Fact]
    public void InvertOcv_FlatRegion_TakesLowEdge()
    {
        // A flat sub-region (same OCV over a SoC span) must not blow up.
        var table = new OcvTable(new[]
        {
            new OcvPoint(0,  48.0, 1),
            new OcvPoint(40, 50.0, 1),
            new OcvPoint(60, 50.0, 1),   // flat: 50.0 V spans 40..60%
            new OcvPoint(100, 54.0, 1),
        });
        double soc = TheveninMath.InvertOcv(table, 50.0);
        Assert.Equal(40.0, soc, precision: 3);   // low edge of the flat region
    }

    [Fact]
    public void Median_HandlesEvenAndOdd()
    {
        Assert.Equal(2.0, TheveninMath.Median(new[] { 1.0, 2.0, 3.0 }));
        Assert.Equal(2.5, TheveninMath.Median(new[] { 1.0, 2.0, 3.0, 4.0 }));
    }

    [Fact]
    public void CoulombStep_ConservesOnChargeThenEqualDischarge()
    {
        const double cap = 100.0;   // Ah
        double soc = 50.0;
        // Charge at +10 A for 1 h, then discharge −10 A for 1 h ⇒ back to start.
        soc = TheveninMath.CoulombStep(soc, netBattA: 10.0, dtMs: 3_600_000L, capacityAh: cap);
        Assert.Equal(60.0, soc, precision: 6);   // +10 Ah = +10% of 100 Ah
        soc = TheveninMath.CoulombStep(soc, netBattA: -10.0, dtMs: 3_600_000L, capacityAh: cap);
        Assert.Equal(50.0, soc, precision: 6);
    }

    [Fact]
    public void CoulombStep_ClampsAtRails()
    {
        Assert.Equal(100.0, TheveninMath.CoulombStep(95, netBattA: 1000, dtMs: 3_600_000L, capacityAh: 10));
        Assert.Equal(0.0,   TheveninMath.CoulombStep(5,  netBattA: -1000, dtMs: 3_600_000L, capacityAh: 10));
    }

    [Fact]
    public void InferBusLoadA_HandComputedDrawCase()
    {
        // OCV=52, V=51 ⇒ a 1 V sag across R=0.1 Ω ⇒ I_batt = −10 A (leaving the
        // battery). No PV, no load terminal ⇒ bus draw = +10 A.
        double? a = TheveninMath.InferBusLoadA(ocv: 52, vObs: 51, rOhms: 0.1, chargeA: 0, dischargeA: 0);
        Assert.NotNull(a);
        Assert.Equal(10.0, a!.Value, precision: 6);
    }

    [Fact]
    public void InferBusLoadA_ChargingReducesNetDraw()
    {
        // Same sag but 4 A of PV coming in ⇒ net bus draw drops by 4 A.
        double? a = TheveninMath.InferBusLoadA(ocv: 52, vObs: 51, rOhms: 0.1, chargeA: 4, dischargeA: 0);
        Assert.Equal(14.0, a!.Value, precision: 6);   // 4 − (−10) = 14 A of loads
    }

    [Fact]
    public void InferBusLoadA_NullOnBadResistance()
    {
        Assert.Null(TheveninMath.InferBusLoadA(ocv: 52, vObs: 51, rOhms: 0, chargeA: 0, dischargeA: 0));
        Assert.Null(TheveninMath.InferBusLoadA(ocv: double.NaN, vObs: 51, rOhms: 0.1, chargeA: 0, dischargeA: 0));
    }
}
