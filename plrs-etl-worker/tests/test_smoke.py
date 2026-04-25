def test_module_imports():
    from plrs_etl import main

    assert callable(main.run)
    assert callable(main.process_event)


def test_parse_ts_handles_z_suffix_and_naive_iso():
    from plrs_etl.main import _parse_ts

    z = _parse_ts("2026-04-25T10:00:00Z")
    iso = _parse_ts("2026-04-25T10:00:00+00:00")
    naive = _parse_ts("2026-04-25T10:00:00")
    assert z == iso == naive
