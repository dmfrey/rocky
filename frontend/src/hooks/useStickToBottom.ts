import { useCallback, useLayoutEffect, useRef } from "react";

/** How close to the bottom (in px) still counts as "reading the latest" - tolerates sub-pixel and rounding gaps. */
const NEAR_BOTTOM_PX = 80;

/**
 * Keeps a scrolling container pinned to its bottom while its content grows - a chat transcript that streams in.
 * New text appears at the bottom edge and older text moves up, instead of the view jumping or animating on every token.
 *
 * It only follows while the reader is at (or near) the bottom: scrolling up to re-read something unpins it, so
 * incoming text never yanks the view away, and scrolling back down (or calling `pin`, e.g. when the reader sends a
 * message) pins it again. The jump is instant on purpose: a smooth scroll restarted on every token is what made
 * streaming look jarring.
 *
 * @param trigger any value that changes whenever the content grows (e.g. the number of characters shown)
 */
export function useStickToBottom<T extends HTMLElement>(trigger: number) {
  const ref = useRef<T>(null);
  const pinned = useRef(true);

  const onScroll = useCallback(() => {
    const element = ref.current;
    if (!element) return;
    pinned.current = element.scrollHeight - element.scrollTop - element.clientHeight <= NEAR_BOTTOM_PX;
  }, []);

  const pin = useCallback(() => {
    pinned.current = true;
  }, []);

  // Layout effect, not a plain one: scroll before the browser paints, so there is no frame at the old position.
  useLayoutEffect(() => {
    const element = ref.current;
    if (element && pinned.current) {
      element.scrollTop = element.scrollHeight;
    }
  }, [trigger]);

  return { ref, onScroll, pin };
}
