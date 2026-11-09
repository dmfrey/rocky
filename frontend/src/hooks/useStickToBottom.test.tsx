import { renderHook } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { useStickToBottom } from "./useStickToBottom";

/** jsdom does no layout, so scrolling geometry is faked: a 1000px transcript in a 400px window. */
function fakeScroller(scrollTop: number) {
  return { scrollHeight: 1000, clientHeight: 400, scrollTop } as HTMLDivElement;
}

function setup(element: HTMLDivElement) {
  const hook = renderHook(({ trigger }) => useStickToBottom<HTMLDivElement>(trigger), { initialProps: { trigger: 0 } });
  hook.result.current.ref.current = element;
  return hook;
}

describe("useStickToBottom", () => {
  it("follows new content to the bottom, with an instant jump", () => {
    const element = fakeScroller(0);
    const { rerender } = setup(element);

    rerender({ trigger: 1 });

    expect(element.scrollTop).toBe(1000);
  });

  it("stops following once the reader scrolls up", () => {
    const element = fakeScroller(0);
    const { result, rerender } = setup(element);

    element.scrollTop = 100; // 500px from the bottom: re-reading something
    result.current.onScroll();
    rerender({ trigger: 1 });

    expect(element.scrollTop).toBe(100);
  });

  it("follows again when the reader scrolls back near the bottom", () => {
    const element = fakeScroller(0);
    const { result, rerender } = setup(element);
    element.scrollTop = 100;
    result.current.onScroll();

    element.scrollTop = 560; // 40px from the bottom - within the tolerance
    result.current.onScroll();
    rerender({ trigger: 1 });

    expect(element.scrollTop).toBe(1000);
  });

  it("pin() resumes following, as when the reader sends a message", () => {
    const element = fakeScroller(0);
    const { result, rerender } = setup(element);
    element.scrollTop = 100;
    result.current.onScroll();

    result.current.pin();
    rerender({ trigger: 1 });

    expect(element.scrollTop).toBe(1000);
  });

  it("does not move the view when nothing has grown", () => {
    const element = fakeScroller(0);
    const { rerender } = setup(element);
    rerender({ trigger: 1 });
    element.scrollTop = 700; // some other re-render, same trigger

    rerender({ trigger: 1 });

    expect(element.scrollTop).toBe(700);
  });
});
