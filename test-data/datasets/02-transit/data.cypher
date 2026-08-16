// ---------------------------------------------------------------------------
// Dataset 02 - Transit network
// ---------------------------------------------------------------------------
// Style : many small, independent statements. One CREATE per node, one
//         MATCH ... CREATE per weighted track segment, and one grouped
//         MATCH ... WHERE x IN [...] CREATE per line / fare zone.
// Scope  : 27 nodes / 64 relationships
//   Station 20, Line 4, Zone 3
//   CONNECTS 20, SERVED_BY 24, IN_ZONE 20
//
// CONNECTS is stored ONCE per adjacent pair (in line direction). Queries are
// expected to traverse it undirected: -[:CONNECTS]- rather than ->.
//
// Interchanges: Central (Red/Blue), Museum (Red/Yellow),
//               Market Square (Red/Green), University (Green/Yellow).
// ---------------------------------------------------------------------------

// -- lines -------------------------------------------------------------------
CREATE (:Line {name: 'Red',    colour: '#c0392b', opened: 1968});
CREATE (:Line {name: 'Blue',   colour: '#2980b9', opened: 1974});
CREATE (:Line {name: 'Green',  colour: '#27ae60', opened: 1989});
CREATE (:Line {name: 'Yellow', colour: '#f1c40f', opened: 2004});

// -- fare zones --------------------------------------------------------------
CREATE (:Zone {name: 'Zone 1', fare: 2.9});
CREATE (:Zone {name: 'Zone 2', fare: 4.1});
CREATE (:Zone {name: 'Zone 3', fare: 5.6});

// -- stations ----------------------------------------------------------------
CREATE (:Station {name: 'Airport',        code: 'APT', platforms: 4, stepFree: true});
CREATE (:Station {name: 'North Gate',     code: 'NGT', platforms: 2, stepFree: true});
CREATE (:Station {name: 'Museum',         code: 'MUS', platforms: 4, stepFree: true});
CREATE (:Station {name: 'Central',        code: 'CEN', platforms: 6, stepFree: true});
CREATE (:Station {name: 'Market Square',  code: 'MKT', platforms: 4, stepFree: false});
CREATE (:Station {name: 'South Gate',     code: 'SGT', platforms: 2, stepFree: true});
CREATE (:Station {name: 'Stadium',        code: 'STA', platforms: 3, stepFree: true});
CREATE (:Station {name: 'West End',       code: 'WES', platforms: 2, stepFree: false});
CREATE (:Station {name: 'Old Town',       code: 'OLD', platforms: 2, stepFree: false});
CREATE (:Station {name: 'Cathedral',      code: 'CAT', platforms: 2, stepFree: true});
CREATE (:Station {name: 'East Park',      code: 'EPK', platforms: 2, stepFree: true});
CREATE (:Station {name: 'Tech Park',      code: 'TPK', platforms: 3, stepFree: true});
CREATE (:Station {name: 'Harbour',        code: 'HRB', platforms: 2, stepFree: true});
CREATE (:Station {name: 'Riverside',      code: 'RIV', platforms: 2, stepFree: false});
CREATE (:Station {name: 'University',     code: 'UNI', platforms: 4, stepFree: true});
CREATE (:Station {name: 'Botanic Garden', code: 'BOT', platforms: 2, stepFree: true});
CREATE (:Station {name: 'Zoo',            code: 'ZOO', platforms: 2, stepFree: false});
CREATE (:Station {name: 'Hospital',       code: 'HOS', platforms: 2, stepFree: true});
CREATE (:Station {name: 'Observatory',    code: 'OBS', platforms: 2, stepFree: false});
CREATE (:Station {name: 'Fair Ground',    code: 'FGR', platforms: 3, stepFree: true});

// -- Red line track: Airport - North Gate - Museum - Central - Market Square -
//    South Gate - Stadium (6 segments) -------------------------------------
MATCH (a:Station {name: 'Airport'}),       (b:Station {name: 'North Gate'})
CREATE (a)-[:CONNECTS {line: 'Red', minutes: 9, distanceKm: 7.4}]->(b);
MATCH (a:Station {name: 'North Gate'}),    (b:Station {name: 'Museum'})
CREATE (a)-[:CONNECTS {line: 'Red', minutes: 4, distanceKm: 2.8}]->(b);
MATCH (a:Station {name: 'Museum'}),        (b:Station {name: 'Central'})
CREATE (a)-[:CONNECTS {line: 'Red', minutes: 2, distanceKm: 1.1}]->(b);
MATCH (a:Station {name: 'Central'}),       (b:Station {name: 'Market Square'})
CREATE (a)-[:CONNECTS {line: 'Red', minutes: 2, distanceKm: 0.9}]->(b);
MATCH (a:Station {name: 'Market Square'}), (b:Station {name: 'South Gate'})
CREATE (a)-[:CONNECTS {line: 'Red', minutes: 5, distanceKm: 3.6}]->(b);
MATCH (a:Station {name: 'South Gate'}),    (b:Station {name: 'Stadium'})
CREATE (a)-[:CONNECTS {line: 'Red', minutes: 6, distanceKm: 4.2}]->(b);

// -- Blue line track: West End - Old Town - Central - Cathedral - East Park -
//    Tech Park (5 segments) --------------------------------------------------
MATCH (a:Station {name: 'West End'}),  (b:Station {name: 'Old Town'})
CREATE (a)-[:CONNECTS {line: 'Blue', minutes: 4, distanceKm: 2.5}]->(b);
MATCH (a:Station {name: 'Old Town'}),  (b:Station {name: 'Central'})
CREATE (a)-[:CONNECTS {line: 'Blue', minutes: 3, distanceKm: 1.6}]->(b);
MATCH (a:Station {name: 'Central'}),   (b:Station {name: 'Cathedral'})
CREATE (a)-[:CONNECTS {line: 'Blue', minutes: 2, distanceKm: 1.2}]->(b);
MATCH (a:Station {name: 'Cathedral'}), (b:Station {name: 'East Park'})
CREATE (a)-[:CONNECTS {line: 'Blue', minutes: 5, distanceKm: 3.3}]->(b);
MATCH (a:Station {name: 'East Park'}), (b:Station {name: 'Tech Park'})
CREATE (a)-[:CONNECTS {line: 'Blue', minutes: 7, distanceKm: 5.8}]->(b);

// -- Green line track: Harbour - Riverside - Market Square - University -
//    Botanic Garden - Zoo (5 segments) ---------------------------------------
MATCH (a:Station {name: 'Harbour'}),        (b:Station {name: 'Riverside'})
CREATE (a)-[:CONNECTS {line: 'Green', minutes: 5, distanceKm: 3.1}]->(b);
MATCH (a:Station {name: 'Riverside'}),      (b:Station {name: 'Market Square'})
CREATE (a)-[:CONNECTS {line: 'Green', minutes: 3, distanceKm: 1.8}]->(b);
MATCH (a:Station {name: 'Market Square'}),  (b:Station {name: 'University'})
CREATE (a)-[:CONNECTS {line: 'Green', minutes: 4, distanceKm: 2.4}]->(b);
MATCH (a:Station {name: 'University'}),     (b:Station {name: 'Botanic Garden'})
CREATE (a)-[:CONNECTS {line: 'Green', minutes: 3, distanceKm: 2.0}]->(b);
MATCH (a:Station {name: 'Botanic Garden'}), (b:Station {name: 'Zoo'})
CREATE (a)-[:CONNECTS {line: 'Green', minutes: 4, distanceKm: 2.9}]->(b);

// -- Yellow line track: Hospital - Museum - Observatory - Fair Ground -
//    University (4 segments) -------------------------------------------------
MATCH (a:Station {name: 'Hospital'}),    (b:Station {name: 'Museum'})
CREATE (a)-[:CONNECTS {line: 'Yellow', minutes: 6, distanceKm: 4.0}]->(b);
MATCH (a:Station {name: 'Museum'}),      (b:Station {name: 'Observatory'})
CREATE (a)-[:CONNECTS {line: 'Yellow', minutes: 5, distanceKm: 3.4}]->(b);
MATCH (a:Station {name: 'Observatory'}), (b:Station {name: 'Fair Ground'})
CREATE (a)-[:CONNECTS {line: 'Yellow', minutes: 4, distanceKm: 2.7}]->(b);
MATCH (a:Station {name: 'Fair Ground'}), (b:Station {name: 'University'})
CREATE (a)-[:CONNECTS {line: 'Yellow', minutes: 6, distanceKm: 4.5}]->(b);

// -- SERVED_BY (24) ----------------------------------------------------------
MATCH (l:Line {name: 'Red'}), (s:Station)
WHERE s.name IN ['Airport', 'North Gate', 'Museum', 'Central', 'Market Square', 'South Gate', 'Stadium']
CREATE (s)-[:SERVED_BY]->(l);

MATCH (l:Line {name: 'Blue'}), (s:Station)
WHERE s.name IN ['West End', 'Old Town', 'Central', 'Cathedral', 'East Park', 'Tech Park']
CREATE (s)-[:SERVED_BY]->(l);

MATCH (l:Line {name: 'Green'}), (s:Station)
WHERE s.name IN ['Harbour', 'Riverside', 'Market Square', 'University', 'Botanic Garden', 'Zoo']
CREATE (s)-[:SERVED_BY]->(l);

MATCH (l:Line {name: 'Yellow'}), (s:Station)
WHERE s.name IN ['Hospital', 'Museum', 'Observatory', 'Fair Ground', 'University']
CREATE (s)-[:SERVED_BY]->(l);

// -- IN_ZONE (20) ------------------------------------------------------------
MATCH (z:Zone {name: 'Zone 1'}), (s:Station)
WHERE s.name IN ['Central', 'Museum', 'Market Square', 'Cathedral', 'Old Town', 'University', 'Riverside']
CREATE (s)-[:IN_ZONE]->(z);

MATCH (z:Zone {name: 'Zone 2'}), (s:Station)
WHERE s.name IN ['North Gate', 'South Gate', 'East Park', 'West End', 'Harbour', 'Observatory', 'Botanic Garden', 'Hospital']
CREATE (s)-[:IN_ZONE]->(z);

MATCH (z:Zone {name: 'Zone 3'}), (s:Station)
WHERE s.name IN ['Airport', 'Stadium', 'Tech Park', 'Zoo', 'Fair Ground']
CREATE (s)-[:IN_ZONE]->(z);
