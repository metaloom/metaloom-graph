/** What the inspector is showing: one node or one relationship, addressed by the id the server sent. */
export interface Selected {
  kind: 'node' | 'link';
  id: string;
}
