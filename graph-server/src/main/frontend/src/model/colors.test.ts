import { describe, expect, test } from 'vitest';
import { colorOfNode, nodeColor, relColor } from './colors';

describe('colours', () => {
  test('are stable for a name', () => {
    // The legend and the canvas colour the same label in two different components with no shared state. Only this
    // property keeps them agreeing.
    expect(nodeColor('Person')).toBe(nodeColor('Person'));
    expect(relColor('KNOWS')).toBe(relColor('KNOWS'));
  });

  test('separate the labels of the demo graph', () => {
    // The first version of this module indexed a twelve-colour palette and gave Person and Product the same colour —
    // the one pair in this graph a user most needs to tell apart. Hence the regression test, and the hue-per-name.
    const labels = ['Person', 'Company', 'Product', 'Employee', 'Legacy System'];
    expect(new Set(labels.map(nodeColor)).size).toBe(labels.length);
  });

  test('separate the relationship types of the demo graph', () => {
    const types = ['KNOWS', 'WORKS_AT', 'BOUGHT', 'MENTORS'];
    expect(new Set(types.map(relColor)).size).toBe(types.length);
  });

  test('a node takes the colour of its first label', () => {
    // One circle can only be one colour, and a node may carry several labels.
    expect(colorOfNode(['Person', 'Employee'])).toBe(nodeColor('Person'));
  });

  test('an unlabelled node is grey, and no label can be that grey', () => {
    const grey = colorOfNode([]);
    expect(grey).toContain('8%');
    const labels = Array.from({ length: 500 }, (_, i) => `Label${i}`);
    expect(labels.map(nodeColor)).not.toContain(grey);
  });
});
