import { useEffect, useState, type PointerEventHandler } from "react";

// Radix can focus a tab during mousedown before the browser updates :focus-visible.
export function useTabInput() {
  const [pointerFocus, setPointerFocus] = useState(false);
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.altKey || event.ctrlKey || event.metaKey ||
          ["Alt", "Control", "Meta", "Shift"].includes(event.key)) return;
      setPointerFocus(false);
    };
    // Listen outside the group too, so Tab/Shift+Tab re-entry restores the ring.
    document.addEventListener("keydown", onKeyDown, true);
    return () => document.removeEventListener("keydown", onKeyDown, true);
  }, []);
  const onPointerDownCapture: PointerEventHandler<HTMLElement> = event => {
    if (event.button === 0 && !event.ctrlKey) setPointerFocus(true);
  };
  // Keep the input state through window blur and focus restoration.
  return { "data-pointer-focus": pointerFocus ? "" : undefined, onPointerDownCapture };
}
