import type { HTMLAttributes } from "react";
import { cn } from "../../lib/utils";

type BadgeTone = "neutral" | "live" | "warn" | "danger" | "info";

export function Badge({ tone = "neutral", className, ...props }: HTMLAttributes<HTMLSpanElement> & { tone?: BadgeTone }) {
  return <span className={cn("badge", `badge--${tone}`, className)} {...props} />;
}
