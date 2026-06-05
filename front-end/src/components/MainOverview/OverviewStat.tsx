export type OverviewStatTone = "green" | "cyan" | "amber" | "violet";

export type OverviewStatItem = {
  caption: string;
  id: string;
  label: string;
  tone: OverviewStatTone;
  value: number | string;
};

type OverviewStatProps = {
  item: OverviewStatItem;
};

export function OverviewStat({ item }: OverviewStatProps) {
  return (
    <div
      className="overview-stat"
      data-tone={item.tone}
    >
      <div className="overview-stat__icon" />
      <div>
        <span>{item.label}</span>
        <strong>{item.value}</strong>
        <p>{item.caption}</p>
      </div>
    </div>
  );
}
