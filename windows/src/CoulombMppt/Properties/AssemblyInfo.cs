using System.Runtime.CompilerServices;

// Expose the internal LearnerCore + LearnerFrame seam to the test project so the
// pure learning pipeline can be driven headless with explicit timestamps.
[assembly: InternalsVisibleTo("CoulombMppt.Tests")]
