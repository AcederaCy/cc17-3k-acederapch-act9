package com.example.flightsearch

import com.example.flightsearch.data.FlightRepository
import android.annotation.SuppressLint
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.flightsearch.data.Airport
import com.example.flightsearch.data.Favorite
import com.example.flightsearch.data.Flight
import com.example.flightsearch.data.FlightDatabase
import com.example.flightsearch.data.FavoriteRepository
import com.example.flightsearch.ui.AirportSuggestionAdapter
import com.example.flightsearch.ui.FlightAdapter
import com.example.flightsearch.ui.FavoriteAdapter
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.*
import com.example.flightsearch.data.PreferencesManager
import android.util.Log
import android.widget.Toast
import android.content.Context
import android.view.inputmethod.InputMethodManager
import kotlinx.coroutines.flow.first
import androidx.activity.addCallback

class MainActivity : AppCompatActivity() {
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var searchEditText: TextInputEditText
    private lateinit var airportAdapter: AirportSuggestionAdapter
    private lateinit var airportRepository: FlightRepository
    private lateinit var flightAdapter: FlightAdapter
    private lateinit var favoriteRepository: FavoriteRepository
    private val searchJob = Job()
    private val searchScope = CoroutineScope(Dispatchers.Main + searchJob)
    private val flightJob = Job()
    private val flightScope = CoroutineScope(Dispatchers.Main + flightJob)
    private lateinit var favoriteAdapter: FavoriteAdapter
    private val favoriteJob = Job()
    private val favoriteScope = CoroutineScope(Dispatchers.Main + favoriteJob)
    private var currentScrollPosition = 0
    private enum class DisplayState {
        FAVORITES,
        SEARCH_RESULTS,
        FLIGHTS
    }
    private var currentDisplayState = DisplayState.FAVORITES

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupDependencies()
        setupRecyclerView()
        setupSearchView()
        setupFlightRecyclerView()
        setupFavorites()
        restoreAppState()
        setupBackNavigation()
    }

    private fun setupDependencies() {
        try {
            Log.d("MainActivity", "Initializing dependencies")
            preferencesManager = PreferencesManager(this)
            val database = FlightDatabase.getDatabase(this)
            Log.d("MainActivity", "Database initialized")

            airportRepository = FlightRepository(database.airportDao())
            favoriteRepository = FavoriteRepository(database.favoriteDao())
            Log.d("MainActivity", "Repositories initialized")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error initializing dependencies", e)
            Toast.makeText(
                this,
                "Error initializing app: ${e.localizedMessage}",
                Toast.LENGTH_LONG
            ).show()
            finish()
        }
    }

    private fun setupRecyclerView() {
        val recyclerView = findViewById<RecyclerView>(R.id.search_results)
        airportAdapter = AirportSuggestionAdapter { airport: Airport ->
            handleAirportSelection(airport)
        }
        recyclerView.apply {
            adapter = airportAdapter as RecyclerView.Adapter<RecyclerView.ViewHolder>
            layoutManager = LinearLayoutManager(this@MainActivity)
            addItemDecoration(
                DividerItemDecoration(this@MainActivity, DividerItemDecoration.VERTICAL)
            )
        }
    }

    private fun setupSearchView() {
        searchEditText = findViewById(R.id.airport_search)

        // Restore saved search query
        lifecycleScope.launch {
            preferencesManager.searchQuery.collect { savedQuery ->
                if (searchEditText.text.toString() != savedQuery) {
                    searchEditText.setText(savedQuery)
                }
            }
        }

        // Setup search text watcher
        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchAirports(s?.toString() ?: "")
            }
        })
    }

    private fun searchAirports(query: String) {
        if (query.isBlank()) {
            showFavorites()
            return
        }

        updateDisplayState(DisplayState.SEARCH_RESULTS)
        // Cancel previous search job
        searchScope.cancel()

        lifecycleScope.launch {
            try {
                Log.d("MainActivity", "Searching for query: $query")
                // Clear favorites adapter first
                favoriteAdapter.submitList(emptyList())

                airportRepository.searchAirports(query).collect { airports ->
                    Log.d("MainActivity", "Found ${airports.size} airports")
                    if (airports.isEmpty()) {
                        Log.d("MainActivity", "No airports found")
                        showEmptyState()
                    } else {
                        hideEmptyState()
                        // Set the airport adapter and its data
                        findViewById<RecyclerView>(R.id.search_results).adapter =
                            airportAdapter as RecyclerView.Adapter<RecyclerView.ViewHolder>
                        airportAdapter.submitList(airports)
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error searching airports", e)
            }
        }
    }

    private fun showEmptyState() {
        findViewById<TextView>(R.id.empty_state).apply {
            visibility = View.VISIBLE
            text = getString(R.string.no_results)
        }
        findViewById<RecyclerView>(R.id.search_results).visibility = View.GONE
    }

    private fun hideEmptyState() {
        findViewById<TextView>(R.id.empty_state).visibility = View.GONE
        findViewById<RecyclerView>(R.id.search_results).visibility = View.VISIBLE
    }

    private fun handleAirportSelection(selectedAirport: Airport) {
        updateDisplayState(DisplayState.FLIGHTS)
        // Hide keyboard and clear search
        searchEditText.clearFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(searchEditText.windowToken, 0)

        // Update title to show selected airport
        supportActionBar?.title = getString(R.string.flights_from, selectedAirport.iataCode)

        // Clear other adapters first
        airportAdapter.submitList(emptyList())
        favoriteAdapter.submitList(emptyList())

        // Set the flight adapter
        val recyclerView = findViewById<RecyclerView>(R.id.search_results)
        recyclerView.adapter = flightAdapter

        // Load and display flights
        lifecycleScope.launch {
            try {
                Log.d("MainActivity", "Loading flights from ${selectedAirport.iataCode}")
                airportRepository.getDestinationAirports(selectedAirport.iataCode)
                    .collect { destinations ->
                        Log.d("MainActivity", "Found ${destinations.size} destinations")
                        val flights = destinations.map { destination ->
                            Flight(
                                departureAirport = selectedAirport,
                                destinationAirport = destination,
                                isFavorite = false // Will be updated by the collect below
                            )
                        }
                        flightAdapter.submitList(flights)

                        // Check favorite status for each flight
                        flights.forEach { flight ->
                            favoriteRepository.isRouteFavorite(
                                flight.departureAirport.iataCode,
                                flight.destinationAirport.iataCode
                            ).collect { isFavorite ->
                                flight.isFavorite = isFavorite
                                val position = flightAdapter.currentList.indexOf(flight)
                                if (position != -1) {
                                    flightAdapter.notifyItemChanged(position)
                                }
                            }
                        }
                    }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error loading flights", e)
                Toast.makeText(
                    this@MainActivity,
                    "Error loading flights: ${e.localizedMessage}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showFavorites() {
        updateDisplayState(DisplayState.FAVORITES)
        // Clear other adapters first
        airportAdapter.submitList(emptyList())
        flightAdapter.submitList(emptyList())

        // Set the favorite adapter immediately
        findViewById<RecyclerView>(R.id.search_results).adapter =
            favoriteAdapter as RecyclerView.Adapter<RecyclerView.ViewHolder>

        // Start observing favorites
        favoriteScope.launch {
            favoriteRepository.getAllFavorites().collect { favorites ->
                if (favorites.isEmpty()) {
                    showEmptyFavorites()
                } else {
                    hideEmptyState()
                    // Load airport information for each favorite
                    favorites.forEach { favorite ->
                        favorite.departureAirport = airportRepository.getAirportByCode(favorite.departureCode)
                        favorite.destinationAirport = airportRepository.getAirportByCode(favorite.destinationCode)
                    }
                    favoriteAdapter.submitList(favorites)
                }
            }
        }
    }

    private fun showEmptyFavorites() {
        // Show empty state message
        findViewById<TextView>(R.id.empty_state).apply {
            visibility = View.VISIBLE
            text = getString(R.string.no_favorites)
        }
    }

    private fun setupFlightRecyclerView() {
        val recyclerView = findViewById<RecyclerView>(R.id.search_results)
        flightAdapter = FlightAdapter { flight: Flight ->
            handleFavoriteClick(flight)
        }
        recyclerView.apply {
            adapter = flightAdapter as RecyclerView.Adapter<RecyclerView.ViewHolder>
            layoutManager = LinearLayoutManager(this@MainActivity)
        }
    }

    private fun handleFavoriteClick(flight: Flight) {
        lifecycleScope.launch {
            val isFavorite = favoriteRepository.isRouteFavorite(flight.departureAirport.iataCode, flight.destinationAirport.iataCode).first()
            if (isFavorite) {
                favoriteRepository.removeFavorite(flight)
                flight.isFavorite = false
            } else {
                favoriteRepository.addFavorite(flight)
                flight.isFavorite = true
            }
            flightAdapter.notifyDataSetChanged()
        }
    }

    private fun setupFavorites() {
        favoriteAdapter = FavoriteAdapter { favorite: Favorite ->
            handleFavoriteDelete(favorite)
        }
    }

    private fun handleFavoriteDelete(favorite: Favorite) {
        lifecycleScope.launch {
            favoriteRepository.removeFavorite(favorite)
        }
    }

    private fun updateDisplayState(state: DisplayState) {
        currentDisplayState = state
        findViewById<RecyclerView>(R.id.search_results).visibility =
            if (state == DisplayState.SEARCH_RESULTS || state == DisplayState.FLIGHTS) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.empty_state).visibility =
            if (state == DisplayState.SEARCH_RESULTS) View.VISIBLE else View.GONE
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this) {
            when (currentDisplayState) {
                DisplayState.FAVORITES -> finish()
                DisplayState.SEARCH_RESULTS -> showFavorites()
                DisplayState.FLIGHTS -> showFavorites()
            }
        }
    }

    private fun restoreAppState() {
        // Restore the app's state (e.g., favorite airports)
        lifecycleScope.launch {
            preferencesManager.searchQuery.first().let { query ->
                searchEditText.setText(query)
            }
        }
    }
}
