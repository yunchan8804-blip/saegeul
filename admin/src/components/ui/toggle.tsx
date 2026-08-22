import * as Switch from "@radix-ui/react-switch";

export function Toggle({ checked, onCheckedChange, label }: { checked: boolean; onCheckedChange: (checked: boolean) => void; label: string }) {
  return (
    <Switch.Root className="toggle" checked={checked} onCheckedChange={onCheckedChange} aria-label={label}>
      <Switch.Thumb className="toggle__thumb" />
    </Switch.Root>
  );
}
