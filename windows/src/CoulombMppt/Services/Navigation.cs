using System.Windows.Controls;

namespace CoulombMppt.Services;

// Tiny page-stack navigator. MainWindow listens to Changed and overlays the
// active sub-page on top of the rail UI. Mirrors the heater client.
public static class Navigation
{
    private static readonly Stack<UserControl> Stack = new();

    public static UserControl? Current => Stack.Count > 0 ? Stack.Peek() : null;

    public static event Action<UserControl?>? Changed;

    public static void Push(UserControl page)
    {
        Stack.Push(page);
        Changed?.Invoke(Current);
    }

    public static void Pop()
    {
        if (Stack.Count == 0) return;
        Stack.Pop();
        Changed?.Invoke(Current);
    }

    public static void PopToRoot()
    {
        Stack.Clear();
        Changed?.Invoke(null);
    }
}
