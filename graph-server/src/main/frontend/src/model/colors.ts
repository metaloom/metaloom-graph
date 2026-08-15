/**
 * Colour by name, not by order of appearance.
 *
 * The sidebar lists the labels in the store; the canvas draws whatever the last query returned. If colours were handed
 * out in the order things showed up, the two would disagree — Person could be blue in the legend and green on the
 * canvas — and a colour would change under the user as soon as a different query ran first. Hashing the name fixes
 * a label's colour for the life of the store, and no state has to be shared between the two panels to keep them
 * consistent.
 */

/**
 * A hue per name, rather than an index into a short palette.
 *
 * A twelve-colour palette collides at once on real data: the first version of this file gave `Person` and `Product`
 * the same colour in the demo graph, which is exactly the pair a user most needs to tell apart. Taking the hue from
 * the hash instead gives 360 buckets, so a collision needs two names to agree modulo 360 rather than modulo 12.
 * Saturation and lightness stay fixed, which is what keeps the result readable rather than arbitrary: nodes are solid
 * enough to carry dark text, relationships are lighter so a line never reads as a circle.
 */
const NODE_SATURATION = 58;
const NODE_LIGHTNESS = 62;
const REL_SATURATION = 52;
const REL_LIGHTNESS = 72;

/** FNV-1a. Any stable hash would do; this one is short and has no surprises on short ASCII strings. */
function hash(value: string): number {
  let h = 0x811c9dc5;
  for (let i = 0; i < value.length; i++) {
    h ^= value.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return h >>> 0;
}

export function nodeColor(label: string | undefined): string {
  if (!label) {
    // Grey, and unreachable by the hash: a node with no label is not a label whose colour happens to be grey.
    return 'hsl(210, 8%, 46%)';
  }
  return `hsl(${hash(label) % 360}, ${NODE_SATURATION}%, ${NODE_LIGHTNESS}%)`;
}

export function relColor(type: string): string {
  return `hsl(${hash(type) % 360}, ${REL_SATURATION}%, ${REL_LIGHTNESS}%)`;
}

/**
 * A node's colour comes from its first label.
 *
 * A node can carry several — the storage format allows three inline and more in an overflow block — and one circle
 * can only be one colour. The first is the one the query engine reports first, and the inspector shows the rest.
 */
export function colorOfNode(labels: string[]): string {
  return nodeColor(labels[0]);
}
