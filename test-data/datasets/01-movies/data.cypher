// ---------------------------------------------------------------------------
// Dataset 01 - Movies
// ---------------------------------------------------------------------------
// Style : ONE single CREATE statement. Every node is bound to a variable and
//         the relationships are appended to the same statement as pattern
//         chains referring back to those variables.
// Scope  : 29 nodes / 52 relationships
//   Person 14, Movie 8, Genre 4, Studio 3
//   ACTED_IN 21, DIRECTED 11, IN_GENRE 12, PRODUCED_BY 8
//
// openCypher: only CREATE with node/relationship patterns, inline map property
// values of type string, integer and list-of-string. No DDL, no procedures.
// ---------------------------------------------------------------------------

CREATE
  // -- people ---------------------------------------------------------------
  (keanu:Person    {name: 'Keanu Reeves',      born: 1964, country: 'Canada'}),
  (carrie:Person   {name: 'Carrie-Anne Moss',  born: 1967, country: 'Canada'}),
  (laurence:Person {name: 'Laurence Fishburne',born: 1961, country: 'USA'}),
  (hugo:Person     {name: 'Hugo Weaving',      born: 1960, country: 'Australia'}),
  (lana:Person     {name: 'Lana Wachowski',    born: 1965, country: 'USA'}),
  (lilly:Person    {name: 'Lilly Wachowski',   born: 1967, country: 'USA'}),
  (tom:Person      {name: 'Tom Hanks',         born: 1956, country: 'USA'}),
  (meg:Person      {name: 'Meg Ryan',          born: 1961, country: 'USA'}),
  (nora:Person     {name: 'Nora Ephron',       born: 1941, country: 'USA'}),
  (rob:Person      {name: 'Rob Reiner',        born: 1947, country: 'USA'}),
  (cuba:Person     {name: 'Cuba Gooding Jr.',  born: 1968, country: 'USA'}),
  (renee:Person    {name: 'Renee Zellweger',   born: 1969, country: 'USA'}),
  (cameron:Person  {name: 'Cameron Crowe',     born: 1957, country: 'USA'}),
  (jack:Person     {name: 'Jack Nicholson',    born: 1937, country: 'USA'}),

  // -- movies ---------------------------------------------------------------
  (matrix1:Movie {title: 'The Matrix',            released: 1999, runtime: 136, rating: 8.7}),
  (matrix2:Movie {title: 'The Matrix Reloaded',   released: 2003, runtime: 138, rating: 7.2}),
  (matrix3:Movie {title: 'The Matrix Revolutions',released: 2003, runtime: 129, rating: 6.7}),
  (sleepless:Movie {title: 'Sleepless in Seattle',released: 1993, runtime: 105, rating: 6.8}),
  (gotMail:Movie   {title: 'You Have Got Mail',   released: 1998, runtime: 119, rating: 6.7}),
  (harrySally:Movie{title: 'When Harry Met Sally',released: 1989, runtime:  96, rating: 7.7}),
  (jerry:Movie     {title: 'Jerry Maguire',       released: 1996, runtime: 139, rating: 7.3}),
  (fewGoodMen:Movie{title: 'A Few Good Men',      released: 1992, runtime: 138, rating: 7.7}),

  // -- genres ---------------------------------------------------------------
  (action:Genre  {name: 'Action'}),
  (scifi:Genre   {name: 'Science Fiction'}),
  (romance:Genre {name: 'Romance'}),
  (drama:Genre   {name: 'Drama'}),

  // -- studios --------------------------------------------------------------
  (warner:Studio   {name: 'Warner Bros.', founded: 1923, city: 'Burbank'}),
  (tristar:Studio  {name: 'TriStar',      founded: 1982, city: 'Culver City'}),
  (columbia:Studio {name: 'Columbia',     founded: 1918, city: 'Culver City'}),

  // -- ACTED_IN (21) --------------------------------------------------------
  (keanu)-[:ACTED_IN    {roles: ['Neo']}]->(matrix1),
  (carrie)-[:ACTED_IN   {roles: ['Trinity']}]->(matrix1),
  (laurence)-[:ACTED_IN {roles: ['Morpheus']}]->(matrix1),
  (hugo)-[:ACTED_IN     {roles: ['Agent Smith']}]->(matrix1),
  (keanu)-[:ACTED_IN    {roles: ['Neo']}]->(matrix2),
  (carrie)-[:ACTED_IN   {roles: ['Trinity']}]->(matrix2),
  (laurence)-[:ACTED_IN {roles: ['Morpheus']}]->(matrix2),
  (hugo)-[:ACTED_IN     {roles: ['Agent Smith']}]->(matrix2),
  (keanu)-[:ACTED_IN    {roles: ['Neo']}]->(matrix3),
  (carrie)-[:ACTED_IN   {roles: ['Trinity']}]->(matrix3),
  (laurence)-[:ACTED_IN {roles: ['Morpheus']}]->(matrix3),
  (hugo)-[:ACTED_IN     {roles: ['Agent Smith']}]->(matrix3),
  (tom)-[:ACTED_IN      {roles: ['Sam Baldwin']}]->(sleepless),
  (meg)-[:ACTED_IN      {roles: ['Annie Reed']}]->(sleepless),
  (tom)-[:ACTED_IN      {roles: ['Joe Fox']}]->(gotMail),
  (meg)-[:ACTED_IN      {roles: ['Kathleen Kelly']}]->(gotMail),
  (meg)-[:ACTED_IN      {roles: ['Sally Albright']}]->(harrySally),
  (cuba)-[:ACTED_IN     {roles: ['Rod Tidwell']}]->(jerry),
  (renee)-[:ACTED_IN    {roles: ['Dorothy Boyd']}]->(jerry),
  (jack)-[:ACTED_IN     {roles: ['Col. Nathan R. Jessup']}]->(fewGoodMen),
  (cuba)-[:ACTED_IN     {roles: ['Cpl. Carl Hammaker']}]->(fewGoodMen),

  // -- DIRECTED (11) --------------------------------------------------------
  (lana)-[:DIRECTED]->(matrix1),
  (lilly)-[:DIRECTED]->(matrix1),
  (lana)-[:DIRECTED]->(matrix2),
  (lilly)-[:DIRECTED]->(matrix2),
  (lana)-[:DIRECTED]->(matrix3),
  (lilly)-[:DIRECTED]->(matrix3),
  (nora)-[:DIRECTED]->(sleepless),
  (nora)-[:DIRECTED]->(gotMail),
  (rob)-[:DIRECTED]->(harrySally),
  (rob)-[:DIRECTED]->(fewGoodMen),
  (cameron)-[:DIRECTED]->(jerry),

  // -- IN_GENRE (12) --------------------------------------------------------
  (matrix1)-[:IN_GENRE]->(action),
  (matrix1)-[:IN_GENRE]->(scifi),
  (matrix2)-[:IN_GENRE]->(action),
  (matrix2)-[:IN_GENRE]->(scifi),
  (matrix3)-[:IN_GENRE]->(action),
  (matrix3)-[:IN_GENRE]->(scifi),
  (sleepless)-[:IN_GENRE]->(romance),
  (gotMail)-[:IN_GENRE]->(romance),
  (harrySally)-[:IN_GENRE]->(romance),
  (jerry)-[:IN_GENRE]->(romance),
  (jerry)-[:IN_GENRE]->(drama),
  (fewGoodMen)-[:IN_GENRE]->(drama),

  // -- PRODUCED_BY (8) ------------------------------------------------------
  (matrix1)-[:PRODUCED_BY   {budget: 63}]->(warner),
  (matrix2)-[:PRODUCED_BY   {budget: 150}]->(warner),
  (matrix3)-[:PRODUCED_BY   {budget: 150}]->(warner),
  (gotMail)-[:PRODUCED_BY   {budget: 65}]->(warner),
  (sleepless)-[:PRODUCED_BY {budget: 21}]->(tristar),
  (jerry)-[:PRODUCED_BY     {budget: 50}]->(tristar),
  (harrySally)-[:PRODUCED_BY{budget: 16}]->(columbia),
  (fewGoodMen)-[:PRODUCED_BY{budget: 40}]->(columbia);
