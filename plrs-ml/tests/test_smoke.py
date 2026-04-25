def test_import():
    from plrs_ml import main

    assert main.app is not None
