namespace CoulombMppt.Data;

// Pure, UI-free, allocation-light math for the Thevenin battery observer.
// Kept free of any store/service/BLE dependency so the learner and the unit
// tests exercise exactly the same code on live frames and on replayed history.
//
// Model: terminal V = OCV(SoC) + I_batt · R, where I_batt is the current INTO
// the battery (charging positive). With everything on the bus, the battery's
// net current is  I_batt = ChargeCurrent − DischargeCurrent − I_busLoads,
// where I_busLoads (house + EA SUN inverter, draw positive) is the unknown we
// solve for from the voltage deficit.
public static class TheveninMath
{
    /// <summary>Robust through-origin slope (R = ΔV/ΔI) from a set of
    /// transient (ΔI, ΔV) observations — the median of the per-event ratios,
    /// which is the Theil–Sen estimator for a line through the origin and
    /// shrugs off the inevitable outliers (an inverter step coinciding with a
    /// PV edge). Returns null if no candidate has |ΔI| ≥ <paramref name="minAbsDi"/>.</summary>
    public static double? RobustSlope(IReadOnlyList<(double dI, double dV)> pairs, double minAbsDi = 0.5)
    {
        if (pairs == null || pairs.Count == 0) return null;
        var slopes = new List<double>(pairs.Count);
        foreach (var (dI, dV) in pairs)
        {
            if (Math.Abs(dI) < minAbsDi) continue;
            double r = dV / dI;
            if (double.IsFinite(r)) slopes.Add(r);
        }
        if (slopes.Count == 0) return null;
        return Median(slopes);
    }

    /// <summary>Median of a list (sorts a copy; does not mutate the input).</summary>
    public static double Median(IReadOnlyList<double> values)
    {
        if (values.Count == 0) return double.NaN;
        var a = values.ToArray();
        Array.Sort(a);
        int n = a.Length;
        return (n & 1) == 1 ? a[n / 2] : (a[n / 2 - 1] + a[n / 2]) / 2.0;
    }

    /// <summary>Rested OCV at a state of charge, by piecewise-linear interpolation
    /// of the learned table (clamped to the end points). Returns NaN if the table
    /// has no points.</summary>
    public static double InterpOcv(OcvTable table, double socPct)
    {
        var p = table.Points;
        if (p.Count == 0) return double.NaN;
        if (p.Count == 1) return p[0].Ocv;
        if (socPct <= p[0].SocPct)            return p[0].Ocv;
        if (socPct >= p[^1].SocPct)           return p[^1].Ocv;
        for (int i = 1; i < p.Count; i++)
        {
            if (socPct <= p[i].SocPct)
            {
                var a = p[i - 1]; var b = p[i];
                double span = b.SocPct - a.SocPct;
                if (span <= 0) return a.Ocv;
                double t = (socPct - a.SocPct) / span;
                return a.Ocv + t * (b.Ocv - a.Ocv);
            }
        }
        return p[^1].Ocv;
    }

    /// <summary>State of charge for a rested terminal voltage — the inverse of
    /// <see cref="InterpOcv"/>, assuming the table's OCV rises monotonically with
    /// SoC (true for NMC). Clamped to [first, last] SoC. Returns NaN if empty.</summary>
    public static double InvertOcv(OcvTable table, double ocv)
    {
        var p = table.Points;
        if (p.Count == 0) return double.NaN;
        if (p.Count == 1) return p[0].SocPct;
        if (ocv <= p[0].Ocv)  return p[0].SocPct;
        if (ocv >= p[^1].Ocv) return p[^1].SocPct;
        for (int i = 1; i < p.Count; i++)
        {
            if (ocv <= p[i].Ocv)
            {
                var a = p[i - 1]; var b = p[i];
                double span = b.Ocv - a.Ocv;
                if (span <= 0) return a.SocPct;   // flat sub-region: take the low edge
                double t = (ocv - a.Ocv) / span;
                return a.SocPct + t * (b.SocPct - a.SocPct);
            }
        }
        return p[^1].SocPct;
    }

    /// <summary>Instantaneous net bus draw (A, positive = drawing from the
    /// battery) read directly from the voltage deficit against the model:
    ///   I_busLoads = ChargeCurrent − DischargeCurrent − (V_obs − OCV)/R.
    /// Far more responsive and stable than differentiating SoC over 30 s.
    /// Returns null when R is non-physical or OCV is unknown.</summary>
    public static double? InferBusLoadA(
        double ocv, double vObs, double rOhms, double chargeA, double dischargeA)
    {
        if (!double.IsFinite(ocv) || rOhms <= 0) return null;
        double iBatt = (vObs - ocv) / rOhms;             // into the battery (signed)
        return chargeA - dischargeA - iBatt;             // loads = PV in − battery in
    }

    /// <summary>Advance a coulomb-counted SoC by one step.
    /// <paramref name="netBattA"/> is current into the battery (charging positive).</summary>
    public static double CoulombStep(double socPct, double netBattA, long dtMs, double capacityAh)
    {
        if (capacityAh <= 0 || dtMs <= 0) return socPct;
        double deltaAh  = netBattA * (dtMs / 3_600_000.0);
        double deltaPct = deltaAh / capacityAh * 100.0;
        return Math.Clamp(socPct + deltaPct, 0.0, 100.0);
    }
}
