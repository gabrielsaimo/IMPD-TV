INSERT INTO pix_keys (uf, pix_key, label) VALUES
 ('SP','pixsp@impd.org.br','Gerência São Paulo'),
 ('MG','pixmg@impd.org.br','Gerência Minas Gerais'),
 ('RJ','pixrj@impd.org.br','Gerência Rio de Janeiro'),
 ('BA','pixba@impd.org.br','Gerência Bahia'),
 ('PR','pixpr@impd.org.br','Gerência Paraná')
ON CONFLICT(uf) DO UPDATE SET pix_key = excluded.pix_key, label = excluded.label;
