import sqlite3, struct, sys
sys.path.insert(0,'.')
from add_parking import curb_tags, lot_tags, pack_coords, bbox, DDL, COORD_SCALE

print("=== curb_tags: real examples from the OSM Street parking wiki ===")
cases = [
    ("no-stopping + maxstay (wiki advanced example)", {
        "highway":"residential",
        "parking:right:restriction:conditional":"no_stopping @ (Mo-Fr 07:00-09:00)",
        "parking:right:maxstay:conditional":"30 minutes @ (Mo-Fr 09:00-18:00)"}),
    ("London residents permit zone", {
        "parking:both":"lane","parking:both:markings":"yes",
        "parking:both:access":"yes",
        "parking:both:access:conditional":"permit @ (Mo-Fr 08:00-18:30)",
        "parking:both:permit":"residents","parking:both:zone":"CA-M"}),
    ("plain no_parking on left", {"parking:left:restriction":"no_parking"}),
    ("parking exists but no rule -> ignored", {"parking:right":"lane"}),
    ("not a parking way at all", {"highway":"primary","maxspeed":"50"}),
]
for name, tags in cases:
    r = curb_tags(tags)
    print(f"  {name}\n    -> {len(r)} side(s): {[ (x['side'], x['restriction'] or x['access'] or x['permit'], x['restr_cond'] or x['access_cond'] or '') for x in r]}")

assert len(curb_tags(cases[0][1]))==1
assert len(curb_tags(cases[1][1]))==1
assert curb_tags(cases[1][1])[0]['permit']=='residents'
assert curb_tags(cases[1][1])[0]['zone']=='CA-M'
assert len(curb_tags(cases[3][1]))==0, "parking:right=lane alone must not create a row"
assert len(curb_tags(cases[4][1]))==0

print("\n=== lot_tags ===")
for t in [{"amenity":"parking","parking":"multi-storey","access":"yes","fee":"yes","capacity":"320","name":"Plaka Garage"},
          {"amenity":"parking","access":"private"},
          {"amenity":"fuel"}]:
    print("  ", t, "->", lot_tags(t))
assert lot_tags({"amenity":"fuel"}) is None
assert lot_tags({"amenity":"parking","capacity":"abc"})["capacity"] is None  # bad int tolerated

print("\n=== coord packing round-trip (must match OsmMap.unpackCoords) ===")
pts=[(37.9755,23.7348),(37.9760,23.7355),(-33.8688,151.2093)]
blob=pack_coords(pts)
n=len(blob)//8
back=[]
for i in range(n):
    la,lo=struct.unpack_from("<ii",blob,i*8)
    back.append((la/COORD_SCALE, lo/COORD_SCALE))
print("  in :",pts); print("  out:",back)
assert all(abs(a[0]-b[0])<1e-6 and abs(a[1]-b[1])<1e-6 for a,b in zip(pts,back))
print("  bytes/point:", len(blob)//len(pts), "(matches int32 pair)")

print("\n=== schema + the app's own spatial query pattern ===")
con=sqlite3.connect(":memory:")
con.execute("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT)")
con.executescript(DDL)
# a curb row in central Athens
c=[(37.9755,23.7348),(37.9760,23.7355)]
mnla,mxla,mnlo,mxlo=bbox(c)
con.execute("INSERT INTO parking_curb VALUES("+",".join("?"*17)+")",
  (1,'right','lane','no_stopping','no_stopping @ (Mo-Fr 07:00-09:00)',None,None,None,None,None,None,None,
   pack_coords(c),mnla,mxla,mnlo,mxlo))
con.execute("INSERT INTO parking_lot VALUES("+",".join("?"*14)+")",
  (2,'Plaka Garage','multi-storey','yes','yes',320,None,'Mo-Su 07:00-23:00',
   37.9757,23.7350,37.9756,37.9758,23.7349,23.7351))
con.commit()

# exactly the WHERE clause OsmMap.queryNear() uses
MARGIN=0.003
def near(table,lat,lon):
    return con.execute(f"SELECT * FROM {table} WHERE maxLat>=? AND minLat<=? AND maxLon>=? AND minLon<=?",
        (lat-MARGIN,lat+MARGIN,lon-MARGIN,lon+MARGIN)).fetchall()
hit=near('parking_curb',37.9757,23.7351); miss=near('parking_curb',38.10,23.90)
print(f"  curb near point : {len(hit)} row(s)  | far away: {len(miss)} row(s)")
print(f"  lot  near point : {len(near('parking_lot',37.9757,23.7351))} row(s)")
assert len(hit)==1 and len(miss)==0
print("  index present:", [r[1] for r in con.execute("PRAGMA index_list(parking_curb)")])
print("\nALL PASS")
